package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.submission.v1.*;
/** One current submission per Assignment/author; each explicit attempt remains immutable. */
final class SubmissionStore {
 record Submission(UUID submissionId,UUID assignmentId,UUID studyId,UUID groupId,UUID spaceId,UUID authorId,long version,long assignmentVersion,String content,String audience,String createdAt,String updatedAt) {}
 record Instructions(UUID submissionId,long submissionVersion,long assignmentVersion,AssignmentStore.Assignment assignment) {}
 record Summary(UUID submissionId,UUID authorId,long version,long assignmentVersion,String updatedAt) {}
 record Page(List<Summary> items,UUID nextAfter) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Submission snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 record Result(Submission submission,boolean applied) {}
 record Mine(Submission submission,boolean canSubmit) {}
 private final ResubmissionRequestStore requests;private final JdbcTemplate sql;private final TransactionTemplate tx;private final AssignmentStore assignments;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 SubmissionStore(DataSource source,AssignmentStore assignments,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);requests=new ResubmissionRequestStore(source,assignments,clock);this.assignments=assignments;this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus s,String reason){return new ResponseStatusException(s,reason);}
 Mine mine(UUID group,UUID space,UUID study,UUID assignment,UUID actor,boolean writer){var parent=assignments.submissionParent(group,space,study,assignment,false);var rows=sql.query("SELECT snapshot::text FROM assignment_submission WHERE assignment_id=? AND author_id=?",(rs,n)->json.readValue(rs.getString(1),Submission.class),assignment,actor);return new Mine(rows.isEmpty()?null:rows.getFirst(),writer&&!parent.studyArchived()&&parent.assignment().state().equals("OPEN"));}
 Submission get(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner){
  var rows=sql.query("SELECT snapshot::text FROM assignment_submission WHERE group_id=? AND space_id=? AND study_id=? AND assignment_id=? AND submission_id=?"+(owner?"":" AND author_id=?"),(rs,n)->json.readValue(rs.getString(1),Submission.class),owner?new Object[]{group,space,study,assignment,id}:new Object[]{group,space,study,assignment,id,actor});
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Submission unavailable");return rows.getFirst();
 }
 Submission lockForFeedback(UUID group,UUID space,UUID study,UUID assignment,UUID id){
  var rows=sql.query("SELECT snapshot::text FROM assignment_submission WHERE group_id=? AND space_id=? AND study_id=? AND assignment_id=? AND submission_id=? FOR UPDATE",(rs,n)->json.readValue(rs.getString(1),Submission.class),group,space,study,assignment,id);
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Submission unavailable");return rows.getFirst();
 }
 /** One bounded statement over Collaboration-owned, immutable revision tables only.
  * Scope and author filtering happen before disclosure; never fall back to current instructions. */
 Instructions instructions(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long version){
  for(var value:List.of(group,space,study,assignment,id,actor))GroupInput.id(value.toString());if(version<1)throw GroupInput.bad();
  var rows=sql.query("SELECT r.assignment_version,a.snapshot::text FROM assignment_submission s JOIN assignment_submission_revision r ON r.submission_id=s.submission_id AND r.assignment_id=s.assignment_id JOIN study_assignment_revision a ON a.assignment_id=r.assignment_id AND a.version=r.assignment_version WHERE s.group_id=? AND s.space_id=? AND s.study_id=? AND s.assignment_id=? AND s.submission_id=? AND r.version=?"+(owner?"":" AND s.author_id=?"),
   (rs,n)->new Instructions(id,version,rs.getLong(1),json.readValue(rs.getString(2),AssignmentStore.Assignment.class)),owner?new Object[]{group,space,study,assignment,id,version}:new Object[]{group,space,study,assignment,id,version,actor});
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Submitted instructions unavailable");return rows.getFirst();
 }
 Page list(UUID group,UUID space,UUID study,UUID assignment,UUID after,int limit){
  assignments.get(group,space,study,assignment);var rows=sql.query("SELECT submission_id,author_id,version,(snapshot->>'assignmentVersion')::bigint,snapshot->>'updatedAt' FROM assignment_submission WHERE group_id=? AND space_id=? AND study_id=? AND assignment_id=?"+(after==null?"":" AND submission_id>?")+" ORDER BY submission_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getLong(4),rs.getString(5)),after==null?new Object[]{group,space,study,assignment,limit+1}:new Object[]{group,space,study,assignment,after,limit+1});var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().submissionId():null);
 }
 History history(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long before,int limit){
  get(group,space,study,assignment,id,actor,owner);var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM assignment_submission_revision WHERE submission_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Submission.class)),id,before,limit+1);var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 Result submit(UUID group,UUID space,UUID study,UUID assignment,UUID actor,long expected,long assignmentVersion,String key,String content,String trace){return submit(group,space,study,assignment,actor,expected,assignmentVersion,key,content,trace,false);}
 Result submitWithFiles(UUID group,UUID space,UUID study,UUID assignment,UUID actor,long expected,long assignmentVersion,String key,String content,String trace){return submit(group,space,study,assignment,actor,expected,assignmentVersion,key,content,trace,true);}
 private Result submit(UUID group,UUID space,UUID study,UUID assignment,UUID actor,long expected,long assignmentVersion,String key,String content,String trace,boolean withFiles){
  for(var id:List.of(group,space,study,assignment,actor))GroupInput.id(id.toString());SubmissionInput.validate(content,assignmentVersion);
  if(expected<0||key==null||!key.matches("[0-9a-f]{32}")||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  return tx.execute(s->{
   var parent=assignments.submissionParent(group,space,study,assignment,true);
   var rows=sql.query("SELECT snapshot::text FROM assignment_submission WHERE assignment_id=? AND author_id=? FOR UPDATE",(rs,n)->json.readValue(rs.getString(1),Submission.class),assignment,actor);var old=rows.isEmpty()?null:rows.getFirst();
   if(old!=null){var retry=sql.query("SELECT base_version,snapshot::text FROM assignment_submission_revision WHERE submission_id=? AND request_key=?",(rs,n)->{
    var v=json.readValue(rs.getString(2),Submission.class);if(rs.getLong(1)!=expected||v.assignmentVersion()!=assignmentVersion||!v.content().equals(content))throw error(HttpStatus.CONFLICT,"Submission key already used with different input");return v;
   },old.submissionId(),key);if(!retry.isEmpty()){boolean recorded=sql.queryForObject("SELECT count(*) FROM submission_file_bundle WHERE submission_id=? AND submission_version=?",Integer.class,old.submissionId(),retry.getFirst().version())>0;if(recorded!=withFiles)throw error(HttpStatus.CONFLICT,"Submission command mode differs");return new Result(retry.getFirst(),false);}}
   if((old==null?0:old.version())!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Submission changed; refresh current version");
   if(parent.studyArchived()||!parent.assignment().state().equals("OPEN"))throw error(HttpStatus.CONFLICT,"Assignment is not accepting submissions");
   if(parent.assignment().version()!=assignmentVersion)throw error(HttpStatus.PRECONDITION_FAILED,"Assignment instructions changed; refresh before submitting");
   String now=clock.instant().truncatedTo(ChronoUnit.MICROS).toString();var v=new Submission(old==null?ids.next():old.submissionId(),assignment,study,group,space,actor,Math.addExact(expected,1),assignmentVersion,content,"AUTHOR_AND_OWNER",old==null?now:old.createdAt(),now);
   if(old==null)sql.update("INSERT INTO assignment_submission VALUES (?,?,?,?,?,?,?,?::jsonb)",v.submissionId(),assignment,study,group,space,actor,v.version(),json.writeValueAsString(v));else sql.update("UPDATE assignment_submission SET version=?,snapshot=?::jsonb WHERE submission_id=?",v.version(),json.writeValueAsString(v),v.submissionId());
   sql.update("INSERT INTO assignment_submission_revision VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)",ids.next(),v.submissionId(),v.version(),actor,Timestamp.from(Instant.parse(now)),key,expected,assignment,assignmentVersion,json.writeValueAsString(v));record(v,trace,old==null?"AssignmentSubmitted":"AssignmentResubmitted");requests.resolve(v,trace);return new Result(v,true);
  });
 }
 private void record(Submission v,String trace,String type){
  var p=SubmissionSnapshotV1.newBuilder().setSubmissionId(v.submissionId().toString()).setAssignmentId(v.assignmentId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setAuthorId(v.authorId().toString()).setVersion(v.version()).setAssignmentVersion(v.assignmentVersion()).setContent(v.content()).setAudience(v.audience()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  UUID id=ids.next();var e=SubmissionEnvelopeV1.newBuilder().setEventId(id.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(Instant.parse(v.updatedAt()).toEpochMilli()).setProducer("collaboration-service").setAggregateType("Submission").setAggregateId(v.submissionId().toString()).setSpaceId(v.spaceId().toString()).setActorId(v.authorId().toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'submission',?,?,?,?, 'collaboration','submission-events','1',?)",id,v.submissionId(),type,Timestamp.from(Instant.parse(v.updatedAt())),trace,e.toByteArray());
 }
}
