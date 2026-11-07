package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;
import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;import io.knowledgebrain.contracts.collaboration.assignment.v1.*;

/** Shared assignment definition; submissions and personal completion belong to separate aggregates. */
final class AssignmentStore {
 record Assignment(UUID assignmentId,UUID studyId,UUID groupId,UUID spaceId,UUID creatorId,long version,String title,String description,String state,String visibility,String createdAt,String updatedAt) {}
 record Summary(UUID assignmentId,long version,String title,String state) {}
 record Page(List<Summary> items,UUID nextAfter) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Assignment snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 record Result(Assignment assignment,boolean created) {}
 record SubmissionParent(Assignment assignment,boolean studyArchived) {}
 SubmissionParent submissionParent(UUID group,UUID space,UUID study,UUID id,boolean lock){
  var parent=lock?studies.lockForChild(group,space,study):studies.get(group,space,study);
  return new SubmissionParent(get(group,space,study,id,lock),parent.state().equals("RETIRED"));
 }
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final StudyStore studies;private final Clock clock;private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 AssignmentStore(DataSource source,StudyStore studies,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.studies=studies;this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus status,String reason){return new ResponseStatusException(status,reason);}
 private String now(){return clock.instant().truncatedTo(ChronoUnit.MICROS).toString();}
 private void validate(UUID group,UUID space,UUID study,UUID actor,String trace){for(var id:List.of(group,space,study,actor))GroupInput.id(id.toString());if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();}
 private void settings(String title,String description){if(title==null||description==null)throw GroupInput.bad();GroupInput.settings(json.writeValueAsBytes(Map.of("title",title,"description",description)));}
 private void writable(StudyStore.Study parent){if(parent.state().equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Study is archived");}
 Assignment get(UUID group,UUID space,UUID study,UUID id){return get(group,space,study,id,false);}
 private Assignment get(UUID group,UUID space,UUID study,UUID id,boolean lock){
  var rows=sql.query("SELECT snapshot::text FROM study_assignment WHERE group_id=? AND space_id=? AND study_id=? AND assignment_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Assignment.class),group,space,study,id);
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Assignment unavailable");return rows.getFirst();
 }
 Page list(UUID group,UUID space,UUID study,UUID after,int limit){
  studies.get(group,space,study);var rows=sql.query("SELECT assignment_id,version,title,state FROM study_assignment WHERE group_id=? AND space_id=? AND study_id=?"+(after==null?"":" AND assignment_id>?")+" ORDER BY assignment_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4)),after==null?new Object[]{group,space,study,limit+1}:new Object[]{group,space,study,after,limit+1});
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().assignmentId():null);
 }
 History history(UUID group,UUID space,UUID study,UUID id,long before,int limit){
  get(group,space,study,id);var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM study_assignment_revision WHERE assignment_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Assignment.class)),id,before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 Result create(UUID group,UUID space,UUID study,UUID actor,String key,String title,String description,String trace){
  validate(group,space,study,actor,trace);settings(title,description);if(key==null||!key.matches("[0-9a-f]{32}"))throw GroupInput.bad();
  return tx.execute(s->{
   var parent=studies.lockForChild(group,space,study);
   var prior=sql.query("SELECT creation_title,creation_description,snapshot::text FROM study_assignment WHERE study_id=? AND creator_id=? AND request_key=?",(rs,n)->{if(!rs.getString(1).equals(title)||!rs.getString(2).equals(description))throw error(HttpStatus.CONFLICT,"Assignment creation key already used");return json.readValue(rs.getString(3),Assignment.class);},study,actor,key);
   if(!prior.isEmpty())return new Result(prior.getFirst(),false);writable(parent);
   String now=now();var v=new Assignment(ids.next(),study,group,space,actor,1,title,description,"DRAFT","PRIVATE",now,now);
   sql.update("INSERT INTO study_assignment VALUES (?,?,?,?,?,?,?,?,1,'DRAFT',?,?::jsonb)",v.assignmentId(),study,group,space,actor,key,title,description,title,json.writeValueAsString(v));record(v,actor,trace,"AssignmentCreated");return new Result(v,true);
  });
 }
 Assignment metadata(UUID group,UUID space,UUID study,UUID id,UUID actor,long expected,String title,String description,String trace){settings(title,description);return change(group,space,study,id,actor,expected,title,description,null,trace);}
 Assignment state(UUID group,UUID space,UUID study,UUID id,UUID actor,long expected,String target,String trace){if(target==null||!Set.of("DRAFT","OPEN","CLOSED","RETIRED").contains(target))throw GroupInput.bad();return change(group,space,study,id,actor,expected,null,null,target,trace);}
 private Assignment change(UUID group,UUID space,UUID study,UUID id,UUID actor,long expected,String title,String description,String target,String trace){
  validate(group,space,study,actor,trace);GroupInput.id(id.toString());if(expected<1)throw GroupInput.bad();
  return tx.execute(s->{
   var parent=studies.lockForChild(group,space,study);var old=get(group,space,study,id,true);
   if(old.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Assignment changed; refresh current version");
   if(target!=null&&old.state().equals(target))return old;
   writable(parent);if(old.state().equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Assignment is archived");
   if(target!=null&&!target.equals("RETIRED")&&!(old.state().equals("DRAFT")&&target.equals("OPEN")||old.state().equals("OPEN")&&target.equals("CLOSED")))throw error(HttpStatus.CONFLICT,"Invalid Assignment lifecycle transition");
   if(target==null&&old.title().equals(title)&&old.description().equals(description))return old;
   var v=new Assignment(id,study,group,space,old.creatorId(),Math.addExact(old.version(),1),target==null?title:old.title(),target==null?description:old.description(),target==null?old.state():target,"PRIVATE",old.createdAt(),now());
   sql.update("UPDATE study_assignment SET version=?,state=?,title=?,snapshot=?::jsonb WHERE assignment_id=?",v.version(),v.state(),v.title(),json.writeValueAsString(v),id);record(v,actor,trace,target==null?"AssignmentRevised":target.equals("RETIRED")?"AssignmentRetired":"AssignmentStateChanged");return v;
  });
 }
 private void record(Assignment v,UUID actor,String trace,String type){
  sql.update("INSERT INTO study_assignment_revision VALUES (?,?,?,?,?,?::jsonb)",ids.next(),v.assignmentId(),v.version(),actor,Timestamp.from(Instant.parse(v.updatedAt())),json.writeValueAsString(v));
  var p=AssignmentSnapshotV1.newBuilder().setAssignmentId(v.assignmentId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setCreatorId(v.creatorId().toString()).setVersion(v.version()).setTitle(v.title()).setDescription(v.description()).setState(v.state()).setVisibility("PRIVATE").setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  UUID event=ids.next();var e=AssignmentEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(Instant.parse(v.updatedAt()).toEpochMilli()).setProducer("collaboration-service").setAggregateType("Assignment").setAggregateId(v.assignmentId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'assignment',?,?,?,?, 'collaboration','assignment-events','1',?)",event,v.assignmentId(),type,Timestamp.from(Instant.parse(v.updatedAt())),trace,e.toByteArray());
 }
}
