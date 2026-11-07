package io.knowledgebrain.collaboration;

import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.study.v1.*;

/** Group Study organization; independent of personal Learning sessions and progress. */
final class StudyStore {
 record Study(UUID studyId,UUID groupId,UUID spaceId,UUID creatorId,long version,String title,String description,String state,String visibility,String createdAt,String updatedAt) {}
 record Summary(UUID studyId,long version,String title,String state) {}
 record Page(List<Summary> items,UUID nextAfter) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Study snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 record Result(Study study,boolean created) {}
 private final JdbcTemplate sql;
 private final TransactionTemplate tx;
 private final Clock clock;
 private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 StudyStore(DataSource source,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus status,String reason){return new ResponseStatusException(status,reason);}
 private String now(){return clock.instant().truncatedTo(ChronoUnit.MICROS).toString();}
 private void validate(UUID group,UUID space,UUID actor,String trace){
  for(var id:List.of(group,space,actor))GroupInput.id(id.toString());
  if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
 }
 private void group(UUID group,UUID space,boolean lock){
  var rows=sql.query("SELECT group_id FROM collaboration_group WHERE group_id=? AND space_id=? AND state='ACTIVE'"+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),group,space);
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Group unavailable");
 }
 Study lockForChild(UUID group,UUID space,UUID study){group(group,space,true);return get(group,space,study,true);}
 Study lockWritable(UUID group,UUID space,UUID study){var v=lockForChild(group,space,study);if(v.state().equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Study is archived");return v;}
 Study get(UUID group,UUID space,UUID study){return get(group,space,study,false);}
 private Study get(UUID group,UUID space,UUID study,boolean lock){
  var rows=sql.query("SELECT snapshot::text FROM group_study WHERE group_id=? AND space_id=? AND study_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Study.class),group,space,study);
  if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Study unavailable");return rows.getFirst();
 }
 Page list(UUID group,UUID space,UUID after,int limit){
  group(group,space,false);var rows=sql.query("SELECT study_id,version,title,state FROM group_study WHERE group_id=? AND space_id=?"+(after==null?"":" AND study_id>?")+" ORDER BY study_id LIMIT ?",
   (rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4)),after==null?new Object[]{group,space,limit+1}:new Object[]{group,space,after,limit+1});
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().studyId():null);
 }
 History history(UUID group,UUID space,UUID study,long before,int limit){
  get(group,space,study);var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM group_study_revision WHERE study_id=? AND version<? ORDER BY version DESC LIMIT ?",
   (rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Study.class)),study,before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 Result create(UUID group,UUID space,UUID actor,String key,String title,String description,String trace){
  validate(group,space,actor,trace);settings(title,description);
  if(key==null||!key.matches("[0-9a-f]{32}"))throw GroupInput.bad();
  return tx.execute(s->{
   group(group,space,true);var prior=sql.query("SELECT creation_title,creation_description,snapshot::text FROM group_study WHERE group_id=? AND creator_id=? AND request_key=?",(rs,n)->{
    if(!rs.getString(1).equals(title)||!rs.getString(2).equals(description))throw error(HttpStatus.CONFLICT,"Study creation key already used");return json.readValue(rs.getString(3),Study.class);
   },group,actor,key);
   if(!prior.isEmpty())return new Result(prior.getFirst(),false);
   String now=now();var v=new Study(ids.next(),group,space,actor,1,title,description,"PLANNED","PRIVATE",now,now);
   sql.update("INSERT INTO group_study VALUES (?,?,?,?,?,?,?,1,'PLANNED',?,?::jsonb)",v.studyId(),group,space,actor,key,title,description,title,json.writeValueAsString(v));
   record(v,actor,trace,"StudyCreated");return new Result(v,true);
  });
 }
 private void settings(String title,String description){
  if(title==null||description==null)throw GroupInput.bad();
  GroupInput.settings(json.writeValueAsBytes(Map.of("title",title,"description",description)));
 }
 Study metadata(UUID group,UUID space,UUID id,UUID actor,long expected,String title,String description,String trace){settings(title,description);return change(group,space,id,actor,expected,title,description,null,trace);}
 Study state(UUID group,UUID space,UUID id,UUID actor,long expected,String target,String trace){
  if(target==null||!Set.of("PLANNED","ACTIVE","COMPLETED","RETIRED").contains(target))throw GroupInput.bad();return change(group,space,id,actor,expected,null,null,target,trace);
 }
 private Study change(UUID group,UUID space,UUID id,UUID actor,long expected,String title,String description,String target,String trace){
  validate(group,space,actor,trace);GroupInput.id(id.toString());if(expected<1)throw GroupInput.bad();
  return tx.execute(s->{
   group(group,space,true);var old=get(group,space,id,true);
   if(old.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Study changed; refresh current version");
   if(target!=null&&old.state().equals(target))return old;
   if(old.state().equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Study is archived");
   if(target!=null&&!target.equals("RETIRED")&&!(old.state().equals("PLANNED")&&target.equals("ACTIVE")||old.state().equals("ACTIVE")&&target.equals("COMPLETED")))throw error(HttpStatus.CONFLICT,"Invalid Study lifecycle transition");
   if(target==null&&old.title().equals(title)&&old.description().equals(description))return old;
   var v=new Study(id,group,space,old.creatorId(),Math.addExact(old.version(),1),target==null?title:old.title(),target==null?description:old.description(),target==null?old.state():target,"PRIVATE",old.createdAt(),now());
   sql.update("UPDATE group_study SET version=?,state=?,title=?,snapshot=?::jsonb WHERE study_id=?",v.version(),v.state(),v.title(),json.writeValueAsString(v),id);
   record(v,actor,trace,target==null?"StudyRevised":target.equals("RETIRED")?"StudyRetired":"StudyStateChanged");return v;
  });
 }
 private void record(Study v,UUID actor,String trace,String type){
  sql.update("INSERT INTO group_study_revision VALUES (?,?,?,?,?,?::jsonb)",ids.next(),v.studyId(),v.version(),actor,Timestamp.from(Instant.parse(v.updatedAt())),json.writeValueAsString(v));
  var p=StudySnapshotV1.newBuilder().setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setCreatorId(v.creatorId().toString())
   .setVersion(v.version()).setTitle(v.title()).setDescription(v.description()).setState(v.state()).setVisibility("PRIVATE").setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  UUID event=ids.next();var e=StudyEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(Instant.parse(v.updatedAt()).toEpochMilli())
   .setProducer("collaboration-service").setAggregateType("Study").setAggregateId(v.studyId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'study',?,?,?,?, 'collaboration','study-events','1',?)",event,v.studyId(),type,Timestamp.from(Instant.parse(v.updatedAt())),trace,e.toByteArray());
 }
}
