package io.knowledgebrain.collaboration;
import java.util.*;import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;
import io.knowledgebrain.contracts.collaboration.feedbackretraction.v1.*;import io.knowledgebrain.contracts.collaboration.graderetraction.v1.*;
/** Resolve immutable alert provenance only after the existing author/OWNER source check. */
final class ReviewEventTargetStore {
 record Target(UUID groupId,UUID spaceId,UUID studyId,UUID assignmentId,UUID submissionId,UUID sourceEventId,UUID sourceAggregateId,long sourceVersion,String kind,UUID reviewId,long reviewVersion,long submissionVersion) {}
 private final JdbcTemplate sql;private final SubmissionStore submissions;
 ReviewEventTargetStore(DataSource ds,SubmissionStore submissions){sql=new JdbcTemplate(ds);this.submissions=submissions;}
 Target get(UUID group,UUID space,UUID study,UUID assignment,UUID submission,UUID actor,boolean owner,UUID event){
  var answer=submissions.get(group,space,study,assignment,submission,actor,owner);
  var rows=sql.query("SELECT aggregatetype,payload FROM outbox_event WHERE id=? AND aggregatetype IN ('submission-feedback','resubmission-request','submission-grade','feedback-retraction','grade-retraction')",(rs,n)->Map.entry(rs.getString(1),rs.getBytes(2)),event);
  if(rows.size()!=1)throw missing();var row=rows.getFirst();var signal=ReviewSignal.decode("collaboration."+row.getKey()+".v1",row.getValue());
  if(!signal.eventId().equals(event)||!signal.groupId().equals(group)||!signal.spaceId().equals(space)||!signal.studyId().equals(study)||!signal.assignmentId().equals(assignment)||!signal.submissionId().equals(submission)||!signal.recipientId().equals(answer.authorId()))throw missing();
  UUID review=signal.aggregateId();long version=signal.sourceVersion();
  try{if(row.getKey().equals("feedback-retraction")){var p=FeedbackRetractionSnapshotV1.parseFrom(FeedbackRetractionEnvelopeV1.parseFrom(row.getValue()).getPayload());review=ReviewSignal.id(p.getFeedbackId());version=p.getFeedbackVersion();}else if(row.getKey().equals("grade-retraction")){var p=GradeRetractionSnapshotV1.parseFrom(GradeRetractionEnvelopeV1.parseFrom(row.getValue()).getPayload());review=ReviewSignal.id(p.getGradeId());version=p.getGradeVersion();}}
  catch(com.google.protobuf.InvalidProtocolBufferException ex){throw new IllegalStateException("Review provenance unavailable");}
  return new Target(group,space,study,assignment,submission,event,signal.aggregateId(),signal.sourceVersion(),signal.kind(),review,version,signal.submissionVersion());
 }
 private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Review source unavailable");}
}
