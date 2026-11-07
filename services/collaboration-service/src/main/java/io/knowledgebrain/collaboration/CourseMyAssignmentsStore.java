package io.knowledgebrain.collaboration;
import java.util.*;import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;
/** Current curriculum only, with own presence; enrollment and private progress are independent. */
final class CourseMyAssignmentsStore {
 static final Set<String> FILTERS=Set.of("OPEN_NOT_SUBMITTED","ALL","OPEN","SUBMITTED","NOT_SUBMITTED");
 record Row(UUID assignmentId,String title,String state,long version,String studyState,
            UUID studyId,String studyTitle,String courseState,UUID submissionId,Long submissionVersion,Long submittedAssignmentVersion) {}
 record Page(List<Row> items,UUID nextAfter) {}
 private final JdbcTemplate sql;private final CourseStore courses;
 CourseMyAssignmentsStore(DataSource ds,CourseStore courses){sql=new JdbcTemplate(ds);this.courses=courses;}
 Page list(UUID group,UUID space,UUID course,UUID actor,UUID after,int limit,String filter){
  for(var id:List.of(group,space,course,actor))GroupInput.id(id.toString());if(after!=null)GroupInput.id(after.toString());
  if(limit<1||limit>100||filter==null||!FILTERS.contains(filter))throw GroupInput.bad();courses.get(group,space,course);
  String condition=switch(filter){
   case "OPEN_NOT_SUBMITTED"->"a.state='OPEN' AND st.state<>'RETIRED' AND c.state<>'RETIRED' AND s.submission_id IS NULL";
   case "OPEN"->"a.state='OPEN' AND st.state<>'RETIRED' AND c.state<>'RETIRED'";
   case "SUBMITTED"->"s.submission_id IS NOT NULL";
   case "NOT_SUBMITTED"->"s.submission_id IS NULL";
   case "ALL"->"TRUE";default->throw GroupInput.bad();
  };
  String query="""
   SELECT a.assignment_id,a.snapshot->>'title' AS title,a.state,a.version,st.state AS study_state,
     st.study_id,st.title AS study_title,c.state AS course_state,s.submission_id,
     s.version AS submission_version,(s.snapshot->>'assignmentVersion')::bigint AS submitted_assignment_version
   FROM group_course c
   JOIN course_study cs ON cs.course_id=c.course_id AND cs.group_id=c.group_id AND cs.space_id=c.space_id
   JOIN group_study st ON st.study_id=cs.study_id AND st.group_id=cs.group_id AND st.space_id=cs.space_id
   JOIN study_assignment a ON a.study_id=st.study_id AND a.group_id=st.group_id AND a.space_id=st.space_id
   LEFT JOIN assignment_submission s ON s.assignment_id=a.assignment_id AND s.author_id=?
     AND s.study_id=a.study_id AND s.group_id=a.group_id AND s.space_id=a.space_id
   WHERE c.group_id=? AND c.space_id=? AND c.course_id=? AND (
   """+condition+")"+(after==null?"":" AND a.assignment_id>?")+" ORDER BY a.assignment_id LIMIT ?";
  var args=new ArrayList<Object>(List.of(actor,group,space,course));if(after!=null)args.add(after);args.add(limit+1);
  var rows=sql.query(query,(rs,n)->new Row(rs.getObject("assignment_id",UUID.class),rs.getString("title"),rs.getString("state"),rs.getLong("version"),rs.getString("study_state"),rs.getObject("study_id",UUID.class),rs.getString("study_title"),rs.getString("course_state"),rs.getObject("submission_id",UUID.class),rs.getObject("submission_version",Long.class),rs.getObject("submitted_assignment_version",Long.class)),args.toArray());
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().assignmentId():null);
 }
}
