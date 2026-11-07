package io.knowledgebrain.collaboration;
import java.util.*;import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;
/** A current OWNER work queue over local metadata, never a score or completion decision. */
final class SubmissionGradingQueueStore {
 static final Set<String> FILTERS=Set.of("NEEDS_GRADING","ALL","MISSING","CURRENT","OUTDATED","RETRACTED","UNAVAILABLE");
 record Row(UUID submissionId,UUID authorId,long version,long assignmentVersion,String updatedAt,String gradingStatus,Long gradeVersion,Long gradedSubmissionVersion,String gradeRetractionState,Long retractionVersion) {}
 record Page(List<Row> items,UUID nextAfter) {}
 private final JdbcTemplate sql;private final AssignmentStore assignments;
 SubmissionGradingQueueStore(DataSource ds,AssignmentStore assignments){sql=new JdbcTemplate(ds);this.assignments=assignments;}
 Page list(UUID group,UUID space,UUID study,UUID assignment,UUID actor,UUID after,int limit,String filter){
  for(var id:List.of(group,space,study,assignment,actor))GroupInput.id(id.toString());if(after!=null)GroupInput.id(after.toString());if(limit<1||limit>100||filter==null||!FILTERS.contains(filter))throw GroupInput.bad();assignments.get(group,space,study,assignment);
  String status="CASE WHEN g.grade_id IS NULL THEN 'MISSING' WHEN g.submission_version>s.version OR (gr.retraction_id IS NOT NULL AND (gr.snapshot->>'state' IS NULL OR gr.snapshot->>'state' NOT IN ('RETRACTED','RESTORED'))) THEN 'UNAVAILABLE' WHEN g.submission_version<s.version THEN 'OUTDATED' WHEN gr.snapshot->>'state'='RETRACTED' THEN 'RETRACTED' ELSE 'CURRENT' END";
  String marker="CASE WHEN gr.retraction_id IS NULL THEN 'NONE' WHEN gr.snapshot->>'state' IN ('RETRACTED','RESTORED') THEN gr.snapshot->>'state' ELSE 'UNKNOWN' END";
  String condition=switch(filter){case "ALL"->"TRUE";case "NEEDS_GRADING"->"grading_status<>'CURRENT'";case "MISSING"->"grading_status='MISSING'";case "CURRENT"->"grading_status='CURRENT'";case "OUTDATED"->"grading_status='OUTDATED'";case "RETRACTED"->"grading_status='RETRACTED'";case "UNAVAILABLE"->"grading_status='UNAVAILABLE'";default->throw GroupInput.bad();};
  String query="WITH rows AS (SELECT s.submission_id,s.author_id,s.version,(s.snapshot->>'assignmentVersion')::bigint AS assignment_version,s.snapshot->>'updatedAt' AS updated_at,"+status+" AS grading_status,g.version AS grade_version,g.submission_version AS graded_submission_version,"+marker+" AS marker_state,gr.version AS retraction_version FROM assignment_submission s LEFT JOIN submission_grade g ON g.submission_id=s.submission_id LEFT JOIN grade_retraction gr ON gr.grade_id=g.grade_id AND gr.grade_version=g.version WHERE s.group_id=? AND s.space_id=? AND s.study_id=? AND s.assignment_id=? AND s.author_id<>?) SELECT * FROM rows WHERE ("+condition+")"+(after==null?"":" AND submission_id>?")+" ORDER BY submission_id LIMIT ?";
  var args=new ArrayList<Object>(List.of(group,space,study,assignment,actor));if(after!=null)args.add(after);args.add(limit+1);
  var rows=sql.query(query,(rs,n)->new Row(rs.getObject("submission_id",UUID.class),rs.getObject("author_id",UUID.class),rs.getLong("version"),rs.getLong("assignment_version"),rs.getString("updated_at"),rs.getString("grading_status"),rs.getObject("grade_version",Long.class),rs.getObject("graded_submission_version",Long.class),rs.getString("marker_state"),rs.getObject("retraction_version",Long.class)),args.toArray());var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().submissionId():null);
 }
}
