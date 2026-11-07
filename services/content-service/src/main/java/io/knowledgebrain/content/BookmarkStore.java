package io.knowledgebrain.content;
import java.util.*;import java.time.*;import java.sql.Timestamp;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.content.bookmark.v1.*;

final class BookmarkStore {
 record Input(String title,String url,String memo) {}
 record Bookmark(UUID bookmarkId,UUID spaceId,String visibility,String state,long version,String title,String url,String memo,String createdAt,String updatedAt) {}
 record Created(Bookmark bookmark,boolean replay) {}
 record Revision(UUID revisionId,UUID bookmarkId,long version,UUID actorId,String occurredAt,Bookmark snapshot) {}
 record Summary(UUID bookmarkId,String title,String url,String state,long version,String updatedAt) {}
 record Page(UUID spaceId,String state,List<Summary> items,String nextAfter) {}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 BookmarkStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private String hash(Input value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
 Created create(UUID space,UUID actor,String key,Input input,String trace){
  BookmarkRules.id(space.toString());BookmarkRules.id(actor.toString());BookmarkRules.input(input);BookmarkRules.trace(trace);
  if(key==null||!key.matches("[0-9a-f]{32}"))throw BookmarkRules.bad();String hash=hash(input);
  return tx.execute(status->{
   var now=clock.instant().toString();var value=new Bookmark(ids.next(),space,"PRIVATE","ACTIVE",1,input.title(),input.url(),input.memo(),now,now);
   int inserted=jdbc.update("INSERT INTO bookmark VALUES (?,?,'ACTIVE',1,?,?,?::jsonb) ON CONFLICT(space_id,request_key) DO NOTHING",value.bookmarkId(),space,key,hash,json.writeValueAsString(value));
   if(inserted==0){var matches=jdbc.queryForObject("SELECT request_hash=? FROM bookmark WHERE space_id=? AND request_key=?",Boolean.class,hash,space,key);if(!Boolean.TRUE.equals(matches))throw new ResponseStatusException(HttpStatus.CONFLICT,"Bookmark request key already used for another input");var original=jdbc.queryForObject("SELECT r.snapshot::text FROM bookmark b JOIN bookmark_revision r USING(bookmark_id) WHERE b.space_id=? AND b.request_key=? AND r.version=1",String.class,space,key);return new Created(json.readValue(original,Bookmark.class),true);}
   revision(value,actor,trace,"BookmarkCreated");return new Created(value,false);
  });
 }
 private Bookmark current(UUID space,UUID id,boolean lock){
  var rows=jdbc.query("SELECT snapshot::text FROM bookmark WHERE space_id=? AND bookmark_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Bookmark.class),space,id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bookmark unavailable");return rows.getFirst();
 }
 Bookmark get(UUID space,UUID id){return current(space,id,false);}
 Bookmark change(UUID space,UUID id,UUID actor,long expected,Input input,boolean retire,String trace){
  if(expected<1)throw BookmarkRules.bad();BookmarkRules.trace(trace);BookmarkRules.id(actor.toString());if(!retire)BookmarkRules.input(input);
  return tx.execute(status->{
   var prior=current(space,id,true);if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Bookmark changed; inspect latest");
   if(prior.state().equals("RETIRED")){if(retire)return prior;throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Bookmark unavailable");}
   if(!retire&&input.equals(new Input(prior.title(),prior.url(),prior.memo())))return prior;
   var value=new Bookmark(id,space,"PRIVATE",retire?"RETIRED":"ACTIVE",Math.addExact(prior.version(),1),retire?prior.title():input.title(),retire?prior.url():input.url(),retire?prior.memo():input.memo(),prior.createdAt(),clock.instant().toString());
   jdbc.update("UPDATE bookmark SET state=?,version=?,snapshot=?::jsonb WHERE bookmark_id=?",value.state(),value.version(),json.writeValueAsString(value),id);revision(value,actor,trace,retire?"BookmarkRetired":"BookmarkUpdated");return value;
  });
 }
 private String cursor(UUID space,String state,UUID last){return Base64.getUrlEncoder().withoutPadding().encodeToString((space+"|"+state+"|"+last).getBytes(StandardCharsets.US_ASCII));}
 private UUID position(UUID space,String state,String raw){
  if(raw==null)return null;
  try{if(!raw.matches("[A-Za-z0-9_-]{1,256}"))throw BookmarkRules.bad();var bytes=Base64.getUrlDecoder().decode(raw);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw BookmarkRules.bad();var parts=new String(bytes,StandardCharsets.US_ASCII).split("\\|",-1);if(parts.length!=3||!parts[0].equals(space.toString())||!parts[1].equals(state))throw BookmarkRules.bad();return BookmarkRules.id(parts[2]);}
  catch(IllegalArgumentException e){throw BookmarkRules.bad();}
 }
 Page list(UUID space,String state,String after,int limit){
  if(state==null||!Set.of("ACTIVE","RETIRED").contains(state)||limit<1||limit>100)throw BookmarkRules.bad();var last=position(space,state,after);
  String select="SELECT bookmark_id,snapshot->>'title',snapshot->>'url',state,version,snapshot->>'updatedAt' FROM bookmark WHERE space_id=? AND state=?";
  org.springframework.jdbc.core.RowMapper<Summary> mapper=(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6));
  var rows=last==null?jdbc.query(select+" ORDER BY bookmark_id LIMIT ?",mapper,space,state,limit+1):jdbc.query(select+" AND bookmark_id>? ORDER BY bookmark_id LIMIT ?",mapper,space,state,last,limit+1);
  var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(space,state,page,rows.size()>limit?cursor(space,state,page.getLast().bookmarkId()):null);
 }
 List<Revision> history(UUID space,UUID id,long before,int limit){
  current(space,id,false);if(before<1||limit<1||limit>10)throw BookmarkRules.bad();
  return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM bookmark_revision WHERE bookmark_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),id,rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Bookmark.class)),id,before,limit);
 }
 private void revision(Bookmark value,UUID actor,String trace,String type){
  var revision=ids.next();var event=ids.next();var now=Instant.parse(value.updatedAt());
  jdbc.update("INSERT INTO bookmark_revision VALUES (?,?,?,?,?,?::jsonb)",revision,value.bookmarkId(),value.version(),actor,Timestamp.from(now),json.writeValueAsString(value));
  var payload=BookmarkChangedPayloadV1.newBuilder().setBookmarkId(value.bookmarkId().toString()).setRevisionId(revision.toString()).setVersion(value.version()).setVisibility("PRIVATE").setState(value.state()).setTitle(value.title()).setUrl(value.url()).setMemo(value.memo()).setCreatedAt(value.createdAt()).setUpdatedAt(value.updatedAt()).build();
  var envelope=BookmarkEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("Bookmark").setAggregateId(value.bookmarkId().toString()).setSpaceId(value.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build();
  jdbc.update("INSERT INTO outbox_event VALUES (?,'bookmark',?,?,?,?, 'content','bookmark-events','1',?)",event,value.bookmarkId(),type,Timestamp.from(now),trace,envelope.toByteArray());
 }
}
