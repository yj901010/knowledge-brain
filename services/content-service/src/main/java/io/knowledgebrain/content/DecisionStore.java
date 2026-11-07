package io.knowledgebrain.content;
import java.util.*;import java.time.*;import java.sql.Timestamp;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.content.decision.v1.*;

final class DecisionStore {
 record Input(String title,String context,String outcome,String rationale,String alternatives,String status) {}
 record Decision(UUID decisionId,UUID spaceId,String visibility,String state,long version,String title,String context,String outcome,String rationale,String alternatives,String status,String createdAt,String updatedAt) {}
 record Created(Decision decision,boolean replay) {}
 record Revision(UUID revisionId,UUID decisionId,long version,UUID actorId,String occurredAt,Decision snapshot) {}
 record Summary(UUID decisionId,String title,String status,String state,long version,String updatedAt) {}
 record Page(UUID spaceId,String state,String status,List<Summary> items,String nextAfter) {}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 DecisionStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private String hash(Input value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
 Created create(UUID space,UUID actor,String key,Input input,String trace){
  DecisionRules.id(space.toString());DecisionRules.id(actor.toString());DecisionRules.input(input);DecisionRules.trace(trace);
  if(key==null||!key.matches("[0-9a-f]{32}"))throw DecisionRules.bad();String hash=hash(input);
  return tx.execute(status->{
   var now=clock.instant().toString();var value=new Decision(ids.next(),space,"PRIVATE","ACTIVE",1,input.title(),input.context(),input.outcome(),input.rationale(),input.alternatives(),input.status(),now,now);
   int inserted=jdbc.update("INSERT INTO decision(decision_id,space_id,state,version,request_key,request_hash,snapshot,status) VALUES (?,?,'ACTIVE',1,?,?,?::jsonb,?) ON CONFLICT(space_id,request_key) DO NOTHING",value.decisionId(),space,key,hash,json.writeValueAsString(value),input.status());
   if(inserted==0){var matches=jdbc.queryForObject("SELECT request_hash=? FROM decision WHERE space_id=? AND request_key=?",Boolean.class,hash,space,key);if(!Boolean.TRUE.equals(matches))throw new ResponseStatusException(HttpStatus.CONFLICT,"Decision request key already used for another input");var original=jdbc.queryForObject("SELECT r.snapshot::text FROM decision b JOIN decision_revision r USING(decision_id) WHERE b.space_id=? AND b.request_key=? AND r.version=1",String.class,space,key);return new Created(json.readValue(original,Decision.class),true);}
   revision(value,actor,trace,"DecisionCreated");return new Created(value,false);
  });
 }
 private Decision current(UUID space,UUID id,boolean lock){
  var rows=jdbc.query("SELECT snapshot::text FROM decision WHERE space_id=? AND decision_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Decision.class),space,id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Decision unavailable");return rows.getFirst();
 }
 Decision get(UUID space,UUID id){return current(space,id,false);}
 Decision change(UUID space,UUID id,UUID actor,long expected,Input input,boolean retire,String trace){
  if(expected<1)throw DecisionRules.bad();DecisionRules.trace(trace);DecisionRules.id(actor.toString());if(!retire)DecisionRules.input(input);
  return tx.execute(status->{
   var prior=current(space,id,true);if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Decision changed; inspect latest");
   if(prior.state().equals("RETIRED")){if(retire)return prior;throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Decision unavailable");}
   if(!retire&&input.equals(new Input(prior.title(),prior.context(),prior.outcome(),prior.rationale(),prior.alternatives(),prior.status())))return prior;
   var value=new Decision(id,space,"PRIVATE",retire?"RETIRED":"ACTIVE",Math.addExact(prior.version(),1),retire?prior.title():input.title(),retire?prior.context():input.context(),retire?prior.outcome():input.outcome(),retire?prior.rationale():input.rationale(),retire?prior.alternatives():input.alternatives(),retire?prior.status():input.status(),prior.createdAt(),clock.instant().toString());
   jdbc.update("UPDATE decision SET state=?,version=?,snapshot=?::jsonb,status=? WHERE decision_id=?",value.state(),value.version(),json.writeValueAsString(value),value.status(),id);revision(value,actor,trace,retire?"DecisionRetired":"DecisionUpdated");return value;
  });
 }
 private String cursor(UUID space,String state,String status,UUID last){return Base64.getUrlEncoder().withoutPadding().encodeToString((space+"|"+state+"|"+status+"|"+last).getBytes(StandardCharsets.US_ASCII));}
 private UUID position(UUID space,String state,String status,String raw){
  if(raw==null)return null;
  try{if(!raw.matches("[A-Za-z0-9_-]{1,256}"))throw DecisionRules.bad();var bytes=Base64.getUrlDecoder().decode(raw);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw DecisionRules.bad();var parts=new String(bytes,StandardCharsets.US_ASCII).split("\\|",-1);if(parts.length!=4||!parts[0].equals(space.toString())||!parts[1].equals(state)||!parts[2].equals(status))throw DecisionRules.bad();return DecisionRules.id(parts[3]);}
  catch(IllegalArgumentException e){throw DecisionRules.bad();}
 }
 Page list(UUID space,String state,String status,String after,int limit){
  if(state==null||!Set.of("ACTIVE","RETIRED").contains(state)||status==null||!Set.of("ALL","DRAFT","DECIDED").contains(status)||limit<1||limit>100)throw DecisionRules.bad();var last=position(space,state,status,after);
  String select="SELECT decision_id,snapshot->>'title',status,state,version,snapshot->>'updatedAt' FROM decision WHERE space_id=? AND state=?";
  var args=new ArrayList<Object>(List.of(space,state));if(!status.equals("ALL")){select+=" AND status=?";args.add(status);}if(last!=null){select+=" AND decision_id>?";args.add(last);}args.add(limit+1);
  var rows=jdbc.query(select+" ORDER BY decision_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6)),args.toArray());
  var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(space,state,status,page,rows.size()>limit?cursor(space,state,status,page.getLast().decisionId()):null);
 }
 List<Revision> history(UUID space,UUID id,long before,int limit){
  current(space,id,false);if(before<1||limit<1||limit>10)throw DecisionRules.bad();
  return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM decision_revision WHERE decision_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),id,rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Decision.class)),id,before,limit);
 }
 private void revision(Decision value,UUID actor,String trace,String type){
  var revision=ids.next();var event=ids.next();var now=Instant.parse(value.updatedAt());
  jdbc.update("INSERT INTO decision_revision VALUES (?,?,?,?,?,?::jsonb)",revision,value.decisionId(),value.version(),actor,Timestamp.from(now),json.writeValueAsString(value));
  var payload=DecisionChangedPayloadV1.newBuilder().setDecisionId(value.decisionId().toString()).setRevisionId(revision.toString()).setVersion(value.version()).setVisibility("PRIVATE").setState(value.state()).setTitle(value.title()).setContext(value.context()).setOutcome(value.outcome()).setRationale(value.rationale()).setAlternatives(value.alternatives()).setStatus(value.status()).setCreatedAt(value.createdAt()).setUpdatedAt(value.updatedAt()).build();
  var envelope=DecisionEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("Decision").setAggregateId(value.decisionId().toString()).setSpaceId(value.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build();
  jdbc.update("INSERT INTO outbox_event VALUES (?,'decision',?,?,?,?, 'content','decision-events','1',?)",event,value.decisionId(),type,Timestamp.from(now),trace,envelope.toByteArray());
 }
}
