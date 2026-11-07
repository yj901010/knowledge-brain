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
import io.knowledgebrain.contracts.collaboration.submissionfeedback.v1.*;

/** Revisioned OWNER comments, independently versioned and pinned to the reviewed answer. */
final class SubmissionFeedbackStore {
 record Feedback(UUID feedbackId,UUID submissionId,UUID assignmentId,UUID studyId,UUID groupId,UUID spaceId,UUID authorId,UUID reviewerId,long version,long submissionVersion,long assignmentVersion,String content,String audience,String createdAt,String updatedAt) {}
 record View(long submissionVersion,long assignmentVersion,Feedback feedback,boolean canWrite) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Feedback snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 private final JdbcTemplate sql;
 private final TransactionTemplate tx;
 private final AssignmentStore assignments;
 private final SubmissionStore submissions;
 private final Clock clock;
 private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();

 SubmissionFeedbackStore(DataSource source,AssignmentStore assignments,SubmissionStore submissions,Clock clock){
  sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);
  this.assignments=assignments;this.submissions=submissions;this.clock=clock;ids=new UuidV7(clock);
 }
 private static ResponseStatusException error(HttpStatus status,String reason){return new ResponseStatusException(status,reason);}
 private boolean writable(AssignmentStore.SubmissionParent p){return !p.studyArchived()&&Set.of("OPEN","CLOSED").contains(p.assignment().state());}
 private Feedback current(UUID id){var rows=sql.query("SELECT snapshot::text FROM submission_feedback WHERE submission_id=?",(rs,n)->json.readValue(rs.getString(1),Feedback.class),id);return rows.isEmpty()?null:rows.getFirst();}

 View get(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner){
  return tx.execute(s->{
   // Same lock order as submit/resubmit and parent lifecycle changes; no HTTP inside.
   var parent=assignments.submissionParent(group,space,study,assignment,true);
   var source=submissions.get(group,space,study,assignment,id,actor,owner);
   return new View(source.version(),source.assignmentVersion(),current(id),owner&&writable(parent));
  });
 }
 Feedback change(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,long expected,SubmissionFeedbackInput input,String trace){
  for(var value:List.of(group,space,study,assignment,id,actor))GroupInput.id(value.toString());
  if(input==null||expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  SubmissionFeedbackInput.validate(input.content(),input.submissionVersion(),input.assignmentVersion());
  return tx.execute(s->{
   var parent=assignments.submissionParent(group,space,study,assignment,true);
   var source=submissions.lockForFeedback(group,space,study,assignment,id);
   var old=current(id);
   if(expected!=(old==null?0:old.version()))throw error(HttpStatus.PRECONDITION_FAILED,"Feedback changed; refresh current version");
   if(!writable(parent))throw error(HttpStatus.CONFLICT,"Feedback is read-only for this Assignment or Study");
   if(input.submissionVersion()!=source.version()||input.assignmentVersion()!=source.assignmentVersion())throw error(HttpStatus.CONFLICT,"Reviewed answer changed; refresh the current submission");
   if(old!=null&&old.content().equals(input.content())&&old.submissionVersion()==source.version()&&old.assignmentVersion()==source.assignmentVersion())return old;
   String now=clock.instant().truncatedTo(ChronoUnit.MICROS).toString();
   var v=new Feedback(old==null?ids.next():old.feedbackId(),id,assignment,study,group,space,source.authorId(),actor,old==null?1:Math.addExact(old.version(),1),source.version(),source.assignmentVersion(),input.content(),"AUTHOR_AND_OWNER",old==null?now:old.createdAt(),now);
   if(old==null)sql.update("INSERT INTO submission_feedback VALUES (?,?,?,?,?,?,?,?,?,?::jsonb)",v.feedbackId(),id,assignment,study,group,space,v.authorId(),v.version(),v.submissionVersion(),json.writeValueAsString(v));
   else sql.update("UPDATE submission_feedback SET version=?,submission_version=?,snapshot=?::jsonb WHERE feedback_id=?",v.version(),v.submissionVersion(),json.writeValueAsString(v),v.feedbackId());
   record(v,trace);return v;
  });
 }
 History history(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long before,int limit){
  submissions.get(group,space,study,assignment,id,actor,owner);var v=current(id);
  if(v==null)return new History(List.of(),null);
  var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM submission_feedback_revision WHERE feedback_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Feedback.class)),v.feedbackId(),before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 private void record(Feedback v,String trace){
  var at=Timestamp.from(Instant.parse(v.updatedAt()));
  sql.update("INSERT INTO submission_feedback_revision VALUES (?,?,?,?,?,?,?,?::jsonb)",ids.next(),v.feedbackId(),v.version(),v.submissionId(),v.submissionVersion(),v.reviewerId(),at,json.writeValueAsString(v));
  var p=SubmissionFeedbackSnapshotV1.newBuilder().setFeedbackId(v.feedbackId().toString()).setSubmissionId(v.submissionId().toString()).setAssignmentId(v.assignmentId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setAuthorId(v.authorId().toString()).setReviewerId(v.reviewerId().toString()).setVersion(v.version()).setSubmissionVersion(v.submissionVersion()).setAssignmentVersion(v.assignmentVersion()).setContent(v.content()).setAudience(v.audience()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt()).build();
  var id=ids.next();var e=SubmissionFeedbackEnvelopeV1.newBuilder().setEventId(id.toString()).setEventType("SubmissionFeedbackRecorded").setEventVersion(1).setOccurredAtEpochMillis(at.toInstant().toEpochMilli()).setProducer("collaboration-service").setAggregateType("SubmissionFeedback").setAggregateId(v.feedbackId().toString()).setSpaceId(v.spaceId().toString()).setActorId(v.reviewerId().toString()).setTraceId(trace).setPayload(p.toByteString()).build();
  sql.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'submission-feedback',?,'SubmissionFeedbackRecorded',?,?,'collaboration','submission-feedback-events','1',?)",id,v.feedbackId(),at,trace,e.toByteArray());
 }
}
