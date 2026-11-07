package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.resubmissionrequest.v1.*;
/** Private request workflow. All writers take Group -> Study -> Assignment -> Submission locks. */
final class ResubmissionRequestStore {
 record Request(UUID requestId,UUID submissionId,UUID assignmentId,UUID studyId,UUID groupId,UUID spaceId,UUID authorId,UUID requestedBy,UUID changedBy,long version,long submissionVersion,long assignmentVersion,String reason,String state,Long resolvedSubmissionVersion,String audience,String createdAt,String updatedAt) {}
 record View(long submissionVersion,long assignmentVersion,Request request,boolean canRequest,boolean canCancel) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Request snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final AssignmentStore assignments;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 ResubmissionRequestStore(DataSource source,AssignmentStore assignments,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.assignments=assignments;this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus s,String reason){return new ResponseStatusException(s,reason);}
 private SubmissionStore.Submission source(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,boolean lock){
  var rows=sql.query("SELECT snapshot::text FROM assignment_submission WHERE group_id=? AND space_id=? AND study_id=? AND assignment_id=? AND submission_id=?"+(owner?"":" AND author_id=?")+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),SubmissionStore.Submission.class),owner?new Object[]{group,space,study,assignment,id}:new Object[]{group,space,study,assignment,id,actor});
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Submission unavailable");return rows.getFirst();
 }
 private Request current(UUID id){var rows=sql.query("SELECT snapshot::text FROM resubmission_request WHERE submission_id=? FOR UPDATE",(rs,n)->json.readValue(rs.getString(1),Request.class),id);return rows.isEmpty()?null:rows.getFirst();}
 View get(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner){return tx.execute(s->{
  var parent=assignments.submissionParent(group,space,study,assignment,true);var answer=source(group,space,study,assignment,id,actor,owner,true);var v=current(id);
  return new View(answer.version(),answer.assignmentVersion(),v,owner&&!parent.studyArchived()&&parent.assignment().state().equals("OPEN"),owner&&!parent.studyArchived()&&Set.of("OPEN","CLOSED").contains(parent.assignment().state())&&v!=null&&v.state().equals("REQUESTED"));
 });}
 Request change(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,long expected,ResubmissionRequestInput input,String trace){
  for(var value:List.of(group,space,study,assignment,id,actor))GroupInput.id(value.toString());ResubmissionRequestInput.validate(input);
  if(expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  return tx.execute(s->{
   var parent=assignments.submissionParent(group,space,study,assignment,true);var answer=source(group,space,study,assignment,id,actor,true,true);var old=current(id);
   if(expected!=(old==null?0:old.version()))throw error(HttpStatus.PRECONDITION_FAILED,"Resubmission request changed; refresh");
   if(parent.studyArchived()||!Set.of("OPEN","CLOSED").contains(parent.assignment().state()))throw error(HttpStatus.CONFLICT,"Request is read-only");
   if(input.action().equals("CANCEL")){
    if(old==null||!Set.of("REQUESTED","CANCELLED").contains(old.state()))throw error(HttpStatus.CONFLICT,"No pending request");
    if(old.state().equals("CANCELLED"))return old;
    return persist(new Request(old.requestId(),id,assignment,study,group,space,old.authorId(),old.requestedBy(),actor,Math.addExact(old.version(),1),old.submissionVersion(),old.assignmentVersion(),old.reason(),"CANCELLED",null,"AUTHOR_AND_OWNER",old.createdAt(),now()),false,trace,"ResubmissionRequestCancelled");
   }
   if(!parent.assignment().state().equals("OPEN"))throw error(HttpStatus.CONFLICT,"Assignment is not accepting submissions");
   if(input.submissionVersion()!=answer.version()||input.assignmentVersion()!=answer.assignmentVersion())throw error(HttpStatus.CONFLICT,"Reviewed answer changed; reopen current submission");
   if(old!=null&&old.state().equals("REQUESTED")&&old.submissionVersion()==answer.version()&&old.assignmentVersion()==answer.assignmentVersion()&&old.reason().equals(input.reason()))return old;
   String now=now();return persist(new Request(old==null?ids.next():old.requestId(),id,assignment,study,group,space,answer.authorId(),actor,actor,old==null?1:Math.addExact(old.version(),1),answer.version(),answer.assignmentVersion(),input.reason(),"REQUESTED",null,"AUTHOR_AND_OWNER",old==null?now:old.createdAt(),now),old==null,trace,"ResubmissionRequested");
  });
 }
 // Called only after the new immutable answer exists in SubmissionStore's SAME local tx.
 // An exact old submission retry returns before this hook; it cannot resolve a later request.
 void resolve(SubmissionStore.Submission answer,String trace){
  if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Submission transaction required");
  var old=current(answer.submissionId());if(old==null||!old.state().equals("REQUESTED")||answer.version()<=old.submissionVersion())return;
  persist(new Request(old.requestId(),old.submissionId(),old.assignmentId(),old.studyId(),old.groupId(),old.spaceId(),old.authorId(),old.requestedBy(),answer.authorId(),Math.addExact(old.version(),1),old.submissionVersion(),old.assignmentVersion(),old.reason(),"RESOLVED",answer.version(),"AUTHOR_AND_OWNER",old.createdAt(),answer.updatedAt()),false,trace,"ResubmissionRequestResolved");
 }
 History history(UUID group,UUID space,UUID study,UUID assignment,UUID id,UUID actor,boolean owner,long before,int limit){
  return tx.execute(s->{source(group,space,study,assignment,id,actor,owner,false);var v=current(id);if(v==null)return new History(List.of(),null);
   var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM resubmission_request_revision WHERE request_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Request.class)),v.requestId(),before,limit+1);
   var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
  });
 }
 private String now(){return clock.instant().truncatedTo(ChronoUnit.MICROS).toString();}
 private Request persist(Request v,boolean initial,String trace,String type){
  if(initial)sql.update("INSERT INTO resubmission_request VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)",v.requestId(),v.submissionId(),v.assignmentId(),v.studyId(),v.groupId(),v.spaceId(),v.authorId(),v.version(),v.submissionVersion(),v.resolvedSubmissionVersion(),json.writeValueAsString(v));
  else sql.update("UPDATE resubmission_request SET version=?,submission_version=?,resolved_submission_version=?,snapshot=?::jsonb WHERE request_id=?",v.version(),v.submissionVersion(),v.resolvedSubmissionVersion(),json.writeValueAsString(v),v.requestId());
  var at=Timestamp.from(Instant.parse(v.updatedAt()));sql.update("INSERT INTO resubmission_request_revision VALUES (?,?,?,?,?,?,?,?,?::jsonb)",ids.next(),v.requestId(),v.version(),v.submissionId(),v.submissionVersion(),v.resolvedSubmissionVersion(),v.changedBy(),at,json.writeValueAsString(v));
  var p=ResubmissionRequestSnapshotV1.newBuilder().setRequestId(v.requestId().toString()).setSubmissionId(v.submissionId().toString()).setAssignmentId(v.assignmentId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setAuthorId(v.authorId().toString()).setRequestedBy(v.requestedBy().toString()).setChangedBy(v.changedBy().toString()).setVersion(v.version()).setSubmissionVersion(v.submissionVersion()).setAssignmentVersion(v.assignmentVersion()).setReason(v.reason()).setState(v.state()).setAudience(v.audience()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  if(v.resolvedSubmissionVersion()!=null)p.setResolvedSubmissionVersion(v.resolvedSubmissionVersion());
  var id=ids.next();var e=ResubmissionRequestEnvelopeV1.newBuilder().setEventId(id.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(at.toInstant().toEpochMilli()).setProducer("collaboration-service").setAggregateType("ResubmissionRequest").setAggregateId(v.requestId().toString()).setSpaceId(v.spaceId().toString()).setActorId(v.changedBy().toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'resubmission-request',?,?,?,?,'collaboration','resubmission-request-events','1',?)",id,v.requestId(),type,at,trace,e.toByteArray());return v;
 }
}
