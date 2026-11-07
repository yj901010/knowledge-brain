package io.knowledgebrain.collaboration;
import java.util.*;import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;
/** Bounded OWNER metadata read over Collaboration-owned aggregates, not a grade/completion decision. */
final class SubmissionReviewStore {
 static final Set<String> FILTERS=Set.of("ALL","NEEDS_FEEDBACK","HAS_FEEDBACK","RETRACTED_FEEDBACK","PENDING_RESUBMISSION");
 record Review(UUID submissionId,UUID authorId,long version,long assignmentVersion,String updatedAt,Long feedbackVersion,Long feedbackSubmissionVersion,String retractionState,boolean needsFeedback,Long requestVersion,String requestState,Long requestedSubmissionVersion) {}
 record Page(List<Review> items,UUID nextAfter) {}
 private final JdbcTemplate sql;private final AssignmentStore assignments;
 SubmissionReviewStore(DataSource source,AssignmentStore assignments){sql=new JdbcTemplate(source);this.assignments=assignments;}
 Page list(UUID group,UUID space,UUID study,UUID assignment,UUID after,int limit,String filter){
  for(var id:List.of(group,space,study,assignment))GroupInput.id(id.toString());if(after!=null)GroupInput.id(after.toString());if(limit<1||limit>100||filter==null||!FILTERS.contains(filter))throw GroupInput.bad();
  assignments.get(group,space,study,assignment);
  String needs="(f.feedback_id IS NULL OR f.submission_version<>s.version OR COALESCE(fr.snapshot->>'state','NONE')='RETRACTED')";
  String condition=switch(filter){case "ALL"->"TRUE";case "NEEDS_FEEDBACK"->needs;case "HAS_FEEDBACK"->"NOT "+needs;case "RETRACTED_FEEDBACK"->"fr.snapshot->>'state'='RETRACTED'";case "PENDING_RESUBMISSION"->"rr.snapshot->>'state'='REQUESTED'";default->throw GroupInput.bad();};
  var rows=sql.query("SELECT s.submission_id,s.author_id,s.version,(s.snapshot->>'assignmentVersion')::bigint,s.snapshot->>'updatedAt',f.version,f.submission_version,COALESCE(fr.snapshot->>'state','NONE'),"+needs+",rr.version,COALESCE(rr.snapshot->>'state','NONE'),(rr.snapshot->>'submissionVersion')::bigint FROM assignment_submission s LEFT JOIN submission_feedback f ON f.submission_id=s.submission_id LEFT JOIN feedback_retraction fr ON fr.feedback_id=f.feedback_id AND fr.feedback_version=f.version LEFT JOIN resubmission_request rr ON rr.submission_id=s.submission_id WHERE s.group_id=? AND s.space_id=? AND s.study_id=? AND s.assignment_id=? AND ("+condition+")"+(after==null?"":" AND s.submission_id>?")+" ORDER BY s.submission_id LIMIT ?",
   (rs,n)->new Review(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getLong(4),rs.getString(5),rs.getObject(6,Long.class),rs.getObject(7,Long.class),rs.getString(8),rs.getBoolean(9),rs.getObject(10,Long.class),rs.getString(11),rs.getObject(12,Long.class)),after==null?new Object[]{group,space,study,assignment,limit+1}:new Object[]{group,space,study,assignment,after,limit+1});
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().submissionId():null);
 }
}
