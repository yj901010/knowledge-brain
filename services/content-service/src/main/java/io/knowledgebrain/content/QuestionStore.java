package io.knowledgebrain.content;
import java.util.*;import java.time.*;import java.sql.Timestamp;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.content.question.v1.*;

final class QuestionStore {
 record Input(String title,String context,String answer,String status) {}
 record Question(UUID questionId,UUID spaceId,String visibility,String state,long version,String title,String context,String answer,String status,String createdAt,String updatedAt) {}
 record Created(Question question,boolean replay) {}
 record Revision(UUID revisionId,UUID questionId,long version,UUID actorId,String occurredAt,Question snapshot) {}
 record Summary(UUID questionId,String title,String status,String state,long version,String updatedAt) {}
 record Page(UUID spaceId,String state,String status,List<Summary> items,String nextAfter) {}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 QuestionStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private String hash(Input value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
 Created create(UUID space,UUID actor,String key,Input input,String trace){
  QuestionRules.id(space.toString());QuestionRules.id(actor.toString());QuestionRules.input(input);QuestionRules.trace(trace);
  if(key==null||!key.matches("[0-9a-f]{32}"))throw QuestionRules.bad();String hash=hash(input);
  return tx.execute(status->{
   var now=clock.instant().toString();var value=new Question(ids.next(),space,"PRIVATE","ACTIVE",1,input.title(),input.context(),input.answer(),input.status(),now,now);
   int inserted=jdbc.update("INSERT INTO question(question_id,space_id,state,version,request_key,request_hash,snapshot,status) VALUES (?,?,'ACTIVE',1,?,?,?::jsonb,?) ON CONFLICT(space_id,request_key) DO NOTHING",value.questionId(),space,key,hash,json.writeValueAsString(value),input.status());
   if(inserted==0){var matches=jdbc.queryForObject("SELECT request_hash=? FROM question WHERE space_id=? AND request_key=?",Boolean.class,hash,space,key);if(!Boolean.TRUE.equals(matches))throw new ResponseStatusException(HttpStatus.CONFLICT,"Question request key already used for another input");var original=jdbc.queryForObject("SELECT r.snapshot::text FROM question b JOIN question_revision r USING(question_id) WHERE b.space_id=? AND b.request_key=? AND r.version=1",String.class,space,key);return new Created(json.readValue(original,Question.class),true);}
   revision(value,actor,trace,"QuestionCreated");return new Created(value,false);
  });
 }
 private Question current(UUID space,UUID id,boolean lock){
  var rows=jdbc.query("SELECT snapshot::text FROM question WHERE space_id=? AND question_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Question.class),space,id);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Question unavailable");return rows.getFirst();
 }
 Question get(UUID space,UUID id){return current(space,id,false);}
 Question change(UUID space,UUID id,UUID actor,long expected,Input input,boolean retire,String trace){
  if(expected<1)throw QuestionRules.bad();QuestionRules.trace(trace);QuestionRules.id(actor.toString());if(!retire)QuestionRules.input(input);
  return tx.execute(status->{
   var prior=current(space,id,true);if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Question changed; inspect latest");
   if(prior.state().equals("RETIRED")){if(retire)return prior;throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Question unavailable");}
   if(!retire&&input.equals(new Input(prior.title(),prior.context(),prior.answer(),prior.status())))return prior;
   var value=new Question(id,space,"PRIVATE",retire?"RETIRED":"ACTIVE",Math.addExact(prior.version(),1),retire?prior.title():input.title(),retire?prior.context():input.context(),retire?prior.answer():input.answer(),retire?prior.status():input.status(),prior.createdAt(),clock.instant().toString());
   jdbc.update("UPDATE question SET state=?,version=?,snapshot=?::jsonb,status=? WHERE question_id=?",value.state(),value.version(),json.writeValueAsString(value),value.status(),id);revision(value,actor,trace,retire?"QuestionRetired":"QuestionUpdated");return value;
  });
 }
 private String cursor(UUID space,String state,String status,UUID last){return Base64.getUrlEncoder().withoutPadding().encodeToString((space+"|"+state+"|"+status+"|"+last).getBytes(StandardCharsets.US_ASCII));}
 private UUID position(UUID space,String state,String status,String raw){
  if(raw==null)return null;
  try{if(!raw.matches("[A-Za-z0-9_-]{1,256}"))throw QuestionRules.bad();var bytes=Base64.getUrlDecoder().decode(raw);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw QuestionRules.bad();var parts=new String(bytes,StandardCharsets.US_ASCII).split("\\|",-1);if(parts.length!=4||!parts[0].equals(space.toString())||!parts[1].equals(state)||!parts[2].equals(status))throw QuestionRules.bad();return QuestionRules.id(parts[3]);}
  catch(IllegalArgumentException e){throw QuestionRules.bad();}
 }
 Page list(UUID space,String state,String status,String after,int limit){
  if(state==null||!Set.of("ACTIVE","RETIRED").contains(state)||status==null||!Set.of("ALL","OPEN","ANSWERED").contains(status)||limit<1||limit>100)throw QuestionRules.bad();var last=position(space,state,status,after);
  String select="SELECT question_id,snapshot->>'title',status,state,version,snapshot->>'updatedAt' FROM question WHERE space_id=? AND state=?";
  var args=new ArrayList<Object>(List.of(space,state));if(!status.equals("ALL")){select+=" AND status=?";args.add(status);}if(last!=null){select+=" AND question_id>?";args.add(last);}args.add(limit+1);
  var rows=jdbc.query(select+" ORDER BY question_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getString(6)),args.toArray());
  var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(space,state,status,page,rows.size()>limit?cursor(space,state,status,page.getLast().questionId()):null);
 }
 List<Revision> history(UUID space,UUID id,long before,int limit){
  current(space,id,false);if(before<1||limit<1||limit>10)throw QuestionRules.bad();
  return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM question_revision WHERE question_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),id,rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Question.class)),id,before,limit);
 }
 private void revision(Question value,UUID actor,String trace,String type){
  var revision=ids.next();var event=ids.next();var now=Instant.parse(value.updatedAt());
  jdbc.update("INSERT INTO question_revision VALUES (?,?,?,?,?,?::jsonb)",revision,value.questionId(),value.version(),actor,Timestamp.from(now),json.writeValueAsString(value));
  var payload=QuestionChangedPayloadV1.newBuilder().setQuestionId(value.questionId().toString()).setRevisionId(revision.toString()).setVersion(value.version()).setVisibility("PRIVATE").setState(value.state()).setTitle(value.title()).setContext(value.context()).setAnswer(value.answer()).setStatus(value.status()).setCreatedAt(value.createdAt()).setUpdatedAt(value.updatedAt()).build();
  var envelope=QuestionEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("Question").setAggregateId(value.questionId().toString()).setSpaceId(value.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build();
  jdbc.update("INSERT INTO outbox_event VALUES (?,'question',?,?,?,?, 'content','question-events','1',?)",event,value.questionId(),type,Timestamp.from(now),trace,envelope.toByteArray());
 }
}
