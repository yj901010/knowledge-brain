package io.knowledgebrain.notification;
import java.time.*;import java.util.*;import io.knowledgebrain.contracts.collaboration.submissionfeedback.v1.*;import io.knowledgebrain.contracts.collaboration.feedbackretraction.v1.*;import io.knowledgebrain.contracts.collaboration.graderetraction.v1.*;
final class ReviewLifecycleFixtures {
 static byte[] event(byte[] source,boolean grade,String state,long version,UUID retraction,long at)throws Exception{
  var original=SubmissionFeedbackEnvelopeV1.parseFrom(source);var f=SubmissionFeedbackSnapshotV1.parseFrom(original.getPayload());var p=FeedbackRetractionSnapshotV1.newBuilder().setRetractionId(retraction.toString()).setFeedbackId(f.getFeedbackId()).setFeedbackVersion(1).setSubmissionId(f.getSubmissionId()).setSubmissionVersion(f.getSubmissionVersion()).setAssignmentId(f.getAssignmentId()).setAssignmentVersion(f.getAssignmentVersion()).setStudyId(f.getStudyId()).setGroupId(f.getGroupId()).setSpaceId(f.getSpaceId()).setAuthorId(f.getAuthorId()).setActorId(f.getReviewerId()).setVersion(version).setState(state).setReason("PRIVATE LIFECYCLE REASON <script>").setAudience("AUTHOR_AND_OWNER").setCreatedAt(Instant.ofEpochMilli(at-100).toString()).setUpdatedAt(Instant.ofEpochMilli(at).toString()).build();
  var e=FeedbackRetractionEnvelopeV1.parseFrom(source).toBuilder().setEventId(ReviewFixtures.ids.next().toString()).setAggregateId(retraction.toString()).setAggregateType(grade?"GradeRetraction":"FeedbackRetraction").setEventType((grade?"Grade":"Feedback")+(state.equals("RETRACTED")?"Retracted":"Restored")).setOccurredAtEpochMillis(at).setPayload(p.toByteString()).build();
  // The two independently published envelopes/snapshots deliberately share wire
  // fields; parse the Grade schema too, then emit its concrete generated type.
  if(grade){var g=GradeRetractionSnapshotV1.parseFrom(p.toByteArray());return GradeRetractionEnvelopeV1.parseFrom(e.toByteArray()).toBuilder().setPayload(g.toByteString()).build().toByteArray();}return e.toByteArray();
 }
}
