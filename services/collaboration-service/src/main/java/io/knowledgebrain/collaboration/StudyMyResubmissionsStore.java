package io.knowledgebrain.collaboration;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Only the freshly authenticated actor's existing requests; reads never resolve a request. */
final class StudyMyResubmissionsStore {
 static final Set<String> FILTERS=Set.of("REQUESTED","RESOLVED","CANCELLED","ALL","UNAVAILABLE");
 record Row(UUID submissionId,UUID assignmentId,String assignmentTitle,String assignmentState,
            long version,long assignmentVersion,String updatedAt,long requestVersion,
            String requestState,long requestedSubmissionVersion,Long resolvedSubmissionVersion) {}
 record Page(List<Row> items,UUID nextAfter) {}
 private final JdbcTemplate sql;
 private final StudyStore studies;
 StudyMyResubmissionsStore(DataSource ds,StudyStore studies){sql=new JdbcTemplate(ds);this.studies=studies;}
 Page list(UUID group,UUID space,UUID study,UUID actor,UUID after,int limit,String filter){
  for(var id:List.of(group,space,study,actor))GroupInput.id(id.toString());
  if(after!=null)GroupInput.id(after.toString());
  if(limit<1||limit>100||filter==null||!FILTERS.contains(filter))throw GroupInput.bad();
  studies.get(group,space,study);
  String status="""
   CASE
    WHEN rr.snapshot->>'state'='REQUESTED' AND rr.submission_version=s.version
         AND rr.resolved_submission_version IS NULL THEN 'REQUESTED'
    WHEN rr.snapshot->>'state'='CANCELLED' AND rr.submission_version<=s.version
         AND rr.resolved_submission_version IS NULL THEN 'CANCELLED'
    WHEN rr.snapshot->>'state'='RESOLVED' AND rr.resolved_submission_version>rr.submission_version
         AND rr.resolved_submission_version<=s.version THEN 'RESOLVED'
    ELSE 'UNAVAILABLE'
   END
   """;
  String condition=filter.equals("ALL")?"TRUE":"request_state=?";
  String query="""
   WITH rows AS (
    SELECT s.submission_id,s.assignment_id,a.snapshot->>'title' AS assignment_title,
      a.state AS assignment_state,s.version,(s.snapshot->>'assignmentVersion')::bigint AS assignment_version,
      s.snapshot->>'updatedAt' AS updated_at,rr.version AS request_version,
   """+status+" AS request_state,"+"""
      rr.submission_version AS requested_submission_version,rr.resolved_submission_version
    FROM assignment_submission s
    JOIN study_assignment a ON a.assignment_id=s.assignment_id AND a.study_id=s.study_id
      AND a.group_id=s.group_id AND a.space_id=s.space_id
    JOIN resubmission_request rr ON rr.submission_id=s.submission_id AND rr.assignment_id=s.assignment_id
      AND rr.study_id=s.study_id AND rr.group_id=s.group_id AND rr.space_id=s.space_id AND rr.author_id=s.author_id
    WHERE s.group_id=? AND s.space_id=? AND s.study_id=? AND s.author_id=?
   ) SELECT * FROM rows WHERE (
   """+condition+")"+(after==null?"":" AND submission_id>?")+" ORDER BY submission_id LIMIT ?";
  var args=new ArrayList<Object>(List.of(group,space,study,actor));
  if(!filter.equals("ALL"))args.add(filter);
  if(after!=null)args.add(after);
  args.add(limit+1);
  var rows=sql.query(query,(rs,n)->new Row(rs.getObject("submission_id",UUID.class),
   rs.getObject("assignment_id",UUID.class),rs.getString("assignment_title"),rs.getString("assignment_state"),
   rs.getLong("version"),rs.getLong("assignment_version"),rs.getString("updated_at"),rs.getLong("request_version"),
   rs.getString("request_state"),rs.getLong("requested_submission_version"),rs.getObject("resolved_submission_version",Long.class)),args.toArray());
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
  return new Page(items,rows.size()>limit?items.getLast().submissionId():null);
 }
}
