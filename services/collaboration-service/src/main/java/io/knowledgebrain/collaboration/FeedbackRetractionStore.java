package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;import io.knowledgebrain.contracts.collaboration.feedbackretraction.v1.*;
/** Audited OWNER retraction/restoration annotation. Original feedback stays immutable. */
final class FeedbackRetractionStore {
 record Retraction(UUID retractionId,UUID feedbackId,long feedbackVersion,UUID submissionId,long submissionVersion,UUID assignmentId,long assignmentVersion,UUID studyId,UUID groupId,UUID spaceId,UUID authorId,UUID actorId,long version,String state,String reason,String audience,String createdAt,String updatedAt) {}
 record View(UUID feedbackId,long feedbackVersion,Retraction retraction,boolean canWrite) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Retraction snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final AssignmentStore assignments;private final SubmissionStore submissions;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 FeedbackRetractionStore(DataSource source,AssignmentStore assignments,SubmissionStore submissions,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.assignments=assignments;this.submissions=submissions;this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus status,String reason){return new ResponseStatusException(status,reason);}
 private boolean writable(AssignmentStore.SubmissionParent p){return !p.studyArchived()&&Set.of("OPEN","CLOSED").contains(p.assignment().state());}
 private SubmissionFeedbackStore.Feedback target(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long feedbackVersion){
  if(feedbackVersion<1)throw GroupInput.bad();
  var rows=sql.query("SELECT r.snapshot::text FROM submission_feedback_revision r JOIN assignment_submission s ON s.submission_id=r.submission_id WHERE s.group_id=? AND s.space_id=? AND s.study_id=? AND s.assignment_id=? AND s.submission_id=? AND r.version=?"+(owner?"":" AND s.author_id=?"),(rs,n)->json.readValue(rs.getString(1),SubmissionFeedbackStore.Feedback.class),owner?new Object[]{group,space,study,assignment,id,feedbackVersion}:new Object[]{group,space,study,assignment,id,feedbackVersion,actor});
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Feedback unavailable");return rows.getFirst();
 }
 private Retraction current(UUID id,long version){var rows=sql.query("SELECT snapshot::text FROM feedback_retraction WHERE feedback_id=? AND feedback_version=?",(rs,n)->json.readValue(rs.getString(1),Retraction.class),id,version);return rows.isEmpty()?null:rows.getFirst();}
 View get(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long feedbackVersion){return tx.execute(s->{
  var parent=assignments.submissionParent(group,space,study,assignment,true);var source=target(group,space,study,assignment,id,actor,owner,feedbackVersion);
  return new View(source.feedbackId(),feedbackVersion,current(source.feedbackId(),feedbackVersion),owner&&writable(parent));
 });}
 Retraction change(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,long feedbackVersion,long expected,FeedbackRetractionInput input,String trace){
  for(var value:List.of(group,space,study,assignment,id,actor))GroupInput.id(value.toString());FeedbackRetractionInput.validate(input);
  if(feedbackVersion<1||expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  return tx.execute(s->{
   var parent=assignments.submissionParent(group,space,study,assignment,true);submissions.lockForFeedback(group,space,study,assignment,id);
   var source=target(group,space,study,assignment,id,actor,true,feedbackVersion);var old=current(source.feedbackId(),feedbackVersion);
   if(expected!=(old==null?0:old.version()))throw error(HttpStatus.PRECONDITION_FAILED,"Retraction changed; refresh");
   if(!writable(parent))throw error(HttpStatus.CONFLICT,"Retraction is read-only for archived parents");
   if(old==null&&input.state().equals("RESTORED"))throw error(HttpStatus.CONFLICT,"Feedback has no retraction to restore");
   if(old!=null&&old.state().equals(input.state())&&old.reason().equals(input.reason()))return old;
   String now=clock.instant().truncatedTo(ChronoUnit.MICROS).toString();
   var v=new Retraction(old==null?ids.next():old.retractionId(),source.feedbackId(),feedbackVersion,id,source.submissionVersion(),assignment,source.assignmentVersion(),study,group,space,source.authorId(),actor,old==null?1:Math.addExact(old.version(),1),input.state(),input.reason(),"AUTHOR_AND_OWNER",old==null?now:old.createdAt(),now);
   if(old==null)sql.update("INSERT INTO feedback_retraction VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)",v.retractionId(),v.feedbackId(),feedbackVersion,id,assignment,study,group,space,v.authorId(),v.version(),json.writeValueAsString(v));
   else sql.update("UPDATE feedback_retraction SET version=?,snapshot=?::jsonb WHERE retraction_id=?",v.version(),json.writeValueAsString(v),v.retractionId());
   record(v,trace);return v;
  });
 }
 History history(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long feedbackVersion,long before,int limit){
  var source=target(group,space,study,assignment,id,actor,owner,feedbackVersion);var v=current(source.feedbackId(),feedbackVersion);if(v==null)return new History(List.of(),null);
  var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM feedback_retraction_revision WHERE retraction_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Retraction.class)),v.retractionId(),before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 private void record(Retraction v,String trace){
  var at=Timestamp.from(Instant.parse(v.updatedAt()));sql.update("INSERT INTO feedback_retraction_revision VALUES (?,?,?,?,?,?,?,?::jsonb)",ids.next(),v.retractionId(),v.version(),v.feedbackId(),v.feedbackVersion(),v.actorId(),at,json.writeValueAsString(v));
  var p=FeedbackRetractionSnapshotV1.newBuilder().setRetractionId(v.retractionId().toString()).setFeedbackId(v.feedbackId().toString()).setFeedbackVersion(v.feedbackVersion()).setSubmissionId(v.submissionId().toString()).setSubmissionVersion(v.submissionVersion()).setAssignmentId(v.assignmentId().toString()).setAssignmentVersion(v.assignmentVersion()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setAuthorId(v.authorId().toString()).setActorId(v.actorId().toString()).setVersion(v.version()).setState(v.state()).setReason(v.reason()).setAudience(v.audience()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt()).build();
  String type=v.state().equals("RETRACTED")?"FeedbackRetracted":"FeedbackRestored";var id=ids.next();var e=FeedbackRetractionEnvelopeV1.newBuilder().setEventId(id.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(at.toInstant().toEpochMilli()).setProducer("collaboration-service").setAggregateType("FeedbackRetraction").setAggregateId(v.retractionId().toString()).setSpaceId(v.spaceId().toString()).setActorId(v.actorId().toString()).setTraceId(trace).setPayload(p.toByteString()).build();
  sql.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'feedback-retraction',?,?,?,?,'collaboration','feedback-retraction-events','1',?)",id,v.retractionId(),type,at,trace,e.toByteArray());
 }
}
