package io.knowledgebrain.collaboration;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared assignments with only the freshly authenticated actor's own submission presence. */
final class GroupMyAssignmentsStore {
 static final Set<String> FILTERS=Set.of("OPEN_NOT_SUBMITTED","ALL","OPEN","SUBMITTED","NOT_SUBMITTED");
 record Row(UUID assignmentId,String title,String state,long version,UUID studyId,String studyTitle,String studyState,
            UUID submissionId,Long submissionVersion,Long submittedAssignmentVersion) {}
 record Page(List<Row> items,UUID nextAfter) {}
 private final JdbcTemplate sql;
 private final GroupStore groups;
 GroupMyAssignmentsStore(DataSource ds,GroupStore groups){sql=new JdbcTemplate(ds);this.groups=groups;}
 Page list(UUID group,UUID space,UUID actor,UUID after,int limit,String filter){
  for(var id:List.of(group,space,actor))GroupInput.id(id.toString());
  if(after!=null)GroupInput.id(after.toString());
  if(limit<1||limit>100||filter==null||!FILTERS.contains(filter))throw GroupInput.bad();
  var config=groups.active(List.of(group));if(config.size()!=1||!space.equals(config.getFirst().spaceId()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Group unavailable");
  String condition=switch(filter){
   case "OPEN_NOT_SUBMITTED"->"a.state='OPEN' AND st.state<>'RETIRED' AND s.submission_id IS NULL";
   case "OPEN"->"a.state='OPEN' AND st.state<>'RETIRED'";
   case "SUBMITTED"->"s.submission_id IS NOT NULL";
   case "NOT_SUBMITTED"->"s.submission_id IS NULL";
   case "ALL"->"TRUE";
   default->throw GroupInput.bad();
  };
  String query="""
   SELECT a.assignment_id,a.snapshot->>'title' AS title,a.state,a.version,a.study_id,st.title AS study_title,st.state AS study_state,
     s.submission_id,s.version AS submission_version,(s.snapshot->>'assignmentVersion')::bigint AS submitted_assignment_version
   FROM study_assignment a
   JOIN group_study st ON st.study_id=a.study_id AND st.group_id=a.group_id AND st.space_id=a.space_id
   LEFT JOIN assignment_submission s ON s.assignment_id=a.assignment_id AND s.author_id=?
     AND s.study_id=a.study_id AND s.group_id=a.group_id AND s.space_id=a.space_id
   WHERE a.group_id=? AND a.space_id=? AND (
   """+condition+")"+(after==null?"":" AND a.assignment_id>?")+" ORDER BY a.assignment_id LIMIT ?";
  var args=new ArrayList<Object>(List.of(actor,group,space));if(after!=null)args.add(after);args.add(limit+1);
  var rows=sql.query(query,(rs,n)->new Row(rs.getObject("assignment_id",UUID.class),rs.getString("title"),
   rs.getString("state"),rs.getLong("version"),rs.getObject("study_id",UUID.class),rs.getString("study_title"),rs.getString("study_state"),rs.getObject("submission_id",UUID.class),
   rs.getObject("submission_version",Long.class),rs.getObject("submitted_assignment_version",Long.class)),args.toArray());
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
  return new Page(items,rows.size()>limit?items.getLast().assignmentId():null);
 }
}
