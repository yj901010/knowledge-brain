package io.knowledgebrain.content;
import java.util.*;import java.time.*;import java.sql.Timestamp;import java.nio.charset.StandardCharsets;
import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.content.recordconcepts.v1.*;

final class RecordConceptStore {
 enum Kind {BOOKMARK("bookmark"),QUESTION("question"),DECISION("decision");final String table;Kind(String table){this.table=table;}static Kind parse(String value){try{return valueOf(value);}catch(RuntimeException e){throw bad();}}}
 record Link(UUID linkId,UUID conceptId){}
 record Snapshot(UUID associationId,long version,List<Link> items){}
 record Parent(UUID spaceId,Kind recordType,UUID recordId,String state,long version,String title){}
 record View(UUID spaceId,Kind recordType,UUID recordId,String recordState,long recordVersion,Snapshot links){}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,long recordVersion,Snapshot snapshot){}
 record Summary(UUID recordId,String title,String state,long version){}
 record Page(UUID spaceId,UUID conceptId,Kind recordType,String state,List<Summary> items,String nextAfter){}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 RecordConceptStore(DataSource ds,Clock clock){jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid record concept input");}
 static UUID id(String v){if(v==null||!v.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw bad();return UUID.fromString(v);}
 static void input(List<UUID> concepts){if(concepts==null||concepts.size()>20||concepts.stream().anyMatch(Objects::isNull)||concepts.stream().distinct().count()!=concepts.size())throw bad();concepts.forEach(v->id(v.toString()));}
 private Parent parent(UUID space,Kind kind,UUID record,boolean lock){
  var rows=jdbc.query("SELECT state,version,snapshot->>'title' FROM "+kind.table+" WHERE space_id=? AND "+kind.table+"_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Parent(space,kind,record,rs.getString(1),rs.getLong(2),rs.getString(3)),space,record);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Private record unavailable");return rows.getFirst();
 }
 private Snapshot current(UUID space,Kind kind,UUID record){var rows=jdbc.query("SELECT snapshot::text FROM record_concepts WHERE space_id=? AND record_type=? AND record_id=?",(rs,n)->json.readValue(rs.getString(1),Snapshot.class),space,kind.name(),record);return rows.isEmpty()?new Snapshot(null,0,List.of()):rows.getFirst();}
 private View view(Parent p,Snapshot s){return new View(p.spaceId(),p.recordType(),p.recordId(),p.state(),p.version(),s);}
 View get(UUID space,Kind kind,UUID record){var p=parent(space,kind,record,false);return view(p,current(space,kind,record));}
 View replace(UUID space,Kind kind,UUID record,UUID actor,long expected,List<UUID> concepts,String trace){
  id(space.toString());id(record.toString());id(actor.toString());input(concepts);if(expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw bad();
  return tx.execute(status->{
   var p=parent(space,kind,record,true);var prior=current(space,kind,record);
   if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Record concept links changed; inspect latest");
   if(!p.state().equals("ACTIVE"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Archived record cannot change links");
   if(prior.items().stream().map(Link::conceptId).toList().equals(concepts))return view(p,prior);
   var stable=new HashMap<UUID,UUID>();prior.items().forEach(v->stable.put(v.conceptId(),v.linkId()));
   var next=new Snapshot(prior.associationId()==null?ids.next():prior.associationId(),Math.addExact(prior.version(),1),concepts.stream().map(v->new Link(stable.containsKey(v)?stable.get(v):ids.next(),v)).toList());
   if(prior.version()==0)jdbc.update("INSERT INTO record_concepts VALUES (?,?,?,?,?,?::jsonb)",next.associationId(),space,kind.name(),record,next.version(),json.writeValueAsString(next));
   else{jdbc.update("UPDATE record_concepts SET version=?,snapshot=?::jsonb WHERE association_id=?",next.version(),json.writeValueAsString(next),next.associationId());jdbc.update("DELETE FROM record_concept_link WHERE association_id=?",next.associationId());}
   int ordinal=0;for(var link:next.items())jdbc.update("INSERT INTO record_concept_link VALUES (?,?,?,?)",link.linkId(),next.associationId(),link.conceptId(),ordinal++);
   var revision=ids.next();var event=ids.next();var now=clock.instant();
   jdbc.update("INSERT INTO record_concept_revision VALUES (?,?,?,?,?,?,?::jsonb)",revision,next.associationId(),next.version(),actor,Timestamp.from(now),p.version(),json.writeValueAsString(next));
   var payload=RecordConceptsChangedPayloadV1.newBuilder().setAssociationId(next.associationId().toString()).setRecordType(kind.name()).setRecordId(record.toString()).setRecordVersion(p.version()).setRecordState(p.state()).setVersion(next.version()).setRevisionId(revision.toString());
   for(var link:next.items())payload.addLinks(RecordConceptLinkV1.newBuilder().setLinkId(link.linkId().toString()).setConceptId(link.conceptId().toString()));
   var envelope=RecordConceptEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("RecordConceptsChanged").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("RecordConceptLinks").setAggregateId(next.associationId().toString()).setSpaceId(space.toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.build().toByteString()).build();
   jdbc.update("INSERT INTO outbox_event VALUES (?,'record-concepts',?,'RecordConceptsChanged',?,?,'content','record-concepts-events','1',?)",event,next.associationId(),Timestamp.from(now),trace,envelope.toByteArray());return view(p,next);
  });
 }
 List<Revision> history(UUID space,Kind kind,UUID record,long before,int limit){parent(space,kind,record,false);if(before<1||limit<1||limit>10)throw bad();var head=current(space,kind,record);if(head.associationId()==null)return List.of();return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,record_version,snapshot::text FROM record_concept_revision WHERE association_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),rs.getLong(5),json.readValue(rs.getString(6),Snapshot.class)),head.associationId(),before,limit);}
 private String cursor(UUID space,UUID concept,Kind kind,String state,UUID last){return Base64.getUrlEncoder().withoutPadding().encodeToString((space+"|"+concept+"|"+kind+"|"+state+"|"+last).getBytes(StandardCharsets.US_ASCII));}
 private UUID position(UUID space,UUID concept,Kind kind,String state,String raw){if(raw==null)return null;try{if(!raw.matches("[A-Za-z0-9_-]{1,256}"))throw bad();var bytes=Base64.getUrlDecoder().decode(raw);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw bad();var parts=new String(bytes,StandardCharsets.US_ASCII).split("\\|",-1);if(parts.length!=5||!parts[0].equals(space.toString())||!parts[1].equals(concept.toString())||!parts[2].equals(kind.name())||!parts[3].equals(state))throw bad();return id(parts[4]);}catch(IllegalArgumentException e){throw bad();}}
 Page references(UUID space,UUID concept,Kind kind,String state,String after,int limit){
  if(state==null||!Set.of("ACTIVE","RETIRED").contains(state)||limit<1||limit>100)throw bad();var last=position(space,concept,kind,state,after);
  String select="SELECT p."+kind.table+"_id,p.snapshot->>'title',p.state,p.version FROM record_concept_link l JOIN record_concepts h USING(association_id) JOIN "+kind.table+" p ON p.space_id=h.space_id AND p."+kind.table+"_id=h.record_id WHERE h.space_id=? AND h.record_type=? AND l.concept_id=? AND p.state=?";
  var args=new ArrayList<Object>(List.of(space,kind.name(),concept,state));if(last!=null){select+=" AND p."+kind.table+"_id>?";args.add(last);}args.add(limit+1);
  var rows=jdbc.query(select+" ORDER BY p."+kind.table+"_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),args.toArray());var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(space,concept,kind,state,page,rows.size()>limit?cursor(space,concept,kind,state,page.getLast().recordId()):null);
 }
}
