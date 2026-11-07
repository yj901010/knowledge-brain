package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.sql.Timestamp;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.rubricassessment.v1.*;
/** Calculation basis is immutable per grade version; the legacy Grade V1 remains unchanged. */
final class RubricGradingStore {
 record Assessment(UUID assessmentId,long version,UUID gradeId,long gradeVersion,UUID submissionId,UUID assignmentId,UUID studyId,UUID groupId,UUID spaceId,UUID authorId,UUID graderId,long submissionVersion,long assignmentVersion,AssignmentRubricStore.Rubric rubric,List<RubricGradingInput.Item> criteria,int score,String reason,String audience,String createdAt){}
 record View(long submissionVersion,long assignmentVersion,AssignmentRubricStore.Rubric rubric,SubmissionGradeStore.Grade grade,Assessment assessment,boolean canWrite){}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final SubmissionGradeStore grades;private final AssignmentRubricStore rubrics;private final SubmissionStore submissions;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 RubricGradingStore(DataSource source,SubmissionGradeStore grades,AssignmentRubricStore rubrics,SubmissionStore submissions,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.grades=grades;this.rubrics=rubrics;this.submissions=submissions;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus s,String reason){return new ResponseStatusException(s,reason);}
 private Assessment basis(UUID grade,long version){var values=sql.query("SELECT snapshot::text FROM submission_rubric_assessment WHERE grade_id=? AND grade_version=?",(rs,n)->json.readValue(rs.getString(1),Assessment.class),grade,version);return values.isEmpty()?null:values.getFirst();}
 private View view(SubmissionGradeStore.View v,AssignmentRubricStore.Rubric rubric){return new View(v.submissionVersion(),v.assignmentVersion(),rubric,v.grade(),v.grade()==null?null:basis(v.grade().gradeId(),v.grade().version()),v.canWrite());}
 View get(UUID group,UUID space,UUID study,UUID assignment,UUID submission,UUID actor,boolean owner){return tx.execute(s->{var v=grades.get(group,space,study,assignment,submission,actor,owner);return view(v,rubrics.get(group,space,study,assignment,owner).rubric());});}
 Assessment forVersion(UUID group,UUID space,UUID study,UUID assignment,UUID submission,long version,UUID actor,boolean owner){
  submissions.get(group,space,study,assignment,submission,actor,owner);var rows=sql.query("SELECT r.snapshot::text FROM submission_grade_revision r JOIN submission_grade g ON g.grade_id=r.grade_id WHERE g.submission_id=? AND r.version=?",(rs,n)->json.readValue(rs.getString(1),SubmissionGradeStore.Grade.class),submission,version);
  if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Grade version unavailable");var grade=rows.getFirst();return basis(grade.gradeId(),version);
 }
 View change(UUID group,UUID space,UUID study,UUID assignment,UUID submission,UUID actor,long expected,RubricGradingInput input,String trace){
  for(var id:List.of(group,space,study,assignment,submission,actor))GroupInput.id(id.toString());RubricGradingInput.validate(input);if(expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  return tx.execute(s->{
   // One DataSource/REQUIRED transaction, same parent locks as submit and legacy grade.
   var source=submissions.get(group,space,study,assignment,submission,actor,true);if(source.authorId().equals(actor))throw error(HttpStatus.FORBIDDEN,"Self grading is forbidden");
   var current=grades.get(group,space,study,assignment,submission,actor,true);var rubric=rubrics.get(group,space,study,assignment,true).rubric();
   if(expected!=(current.grade()==null?0:current.grade().version()))throw error(HttpStatus.PRECONDITION_FAILED,"Grade changed; refresh");
   if(!current.canWrite())throw error(HttpStatus.CONFLICT,"Archived or DRAFT parent is read-only");
   if(input.submissionVersion()!=current.submissionVersion()||input.assignmentVersion()!=current.assignmentVersion())throw error(HttpStatus.CONFLICT,"Reviewed submission changed");
   var old=current.grade()==null?null:basis(current.grade().gradeId(),current.grade().version());
   if(input.mode().equals("DIRECT")){
    var command=new SubmissionGradeInput(input.score(),input.reason(),input.submissionVersion(),input.assignmentVersion());
    var grade=old==null?grades.change(group,space,study,assignment,submission,actor,expected,command,trace):grades.recordCalculated(group,space,study,assignment,submission,actor,expected,command,trace);
    return new View(current.submissionVersion(),current.assignmentVersion(),rubric,grade,null,true);
   }
   if(rubric==null||rubric.criteria().isEmpty()||rubric.version()!=input.rubricVersion()||rubric.criteria().size()!=input.criteria().size())throw error(HttpStatus.CONFLICT,"Frozen rubric changed or unavailable");
   int total=0;for(int i=0;i<rubric.criteria().size();i++){var criterion=rubric.criteria().get(i);var score=input.criteria().get(i);if(!criterion.criterionId().equals(score.criterionId()))throw error(HttpStatus.CONFLICT,"Exact frozen rubric order and IDs required");if(score.score()>criterion.maxPoints())throw GroupInput.bad();total+=score.score();}
   if(old!=null&&old.submissionVersion()==current.submissionVersion()&&old.assignmentVersion()==current.assignmentVersion()&&old.rubric().equals(rubric)&&old.criteria().equals(input.criteria())&&old.reason().equals(input.reason()))return view(current,rubric);
   var grade=grades.recordCalculated(group,space,study,assignment,submission,actor,expected,new SubmissionGradeInput(total,input.reason(),input.submissionVersion(),input.assignmentVersion()),trace);
   var assessment=new Assessment(ids.next(),1,grade.gradeId(),grade.version(),submission,assignment,study,group,space,grade.authorId(),actor,grade.submissionVersion(),grade.assignmentVersion(),rubric,input.criteria(),total,input.reason(),"AUTHOR_AND_OWNER",grade.updatedAt());
   sql.update("INSERT INTO submission_rubric_assessment VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",assessment.assessmentId(),grade.gradeId(),grade.version(),submission,grade.submissionVersion(),assignment,study,group,space,grade.authorId(),actor,rubric.rubricId(),rubric.version(),grade.assignmentVersion(),total,json.writeValueAsString(assessment));
   record(assessment,trace);return new View(current.submissionVersion(),current.assignmentVersion(),rubric,grade,assessment,true);
  });
 }
 private void record(Assessment v,String trace){
  var rubric=v.rubric();var rb=RubricDefinitionV1.newBuilder().setRubricId(rubric.rubricId().toString()).setAssignmentId(rubric.assignmentId().toString()).setStudyId(rubric.studyId().toString()).setGroupId(rubric.groupId().toString()).setSpaceId(rubric.spaceId().toString()).setCreatorId(rubric.creatorId().toString()).setActorId(rubric.actorId().toString()).setVersion(rubric.version()).setAssignmentVersion(rubric.assignmentVersion()).setCreatedAt(rubric.createdAt()).setUpdatedAt(rubric.updatedAt());
  for(var n:rubric.criteria())rb.addCriteria(RubricCriterionV1.newBuilder().setCriterionId(n.criterionId().toString()).setTitle(n.title()).setDescription(n.description()).setMaxPoints(n.maxPoints()));
  var p=RubricAssessmentSnapshotV1.newBuilder().setAssessmentId(v.assessmentId().toString()).setVersion(1).setGradeId(v.gradeId().toString()).setGradeVersion(v.gradeVersion()).setSubmissionId(v.submissionId().toString()).setAssignmentId(v.assignmentId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setAuthorId(v.authorId().toString()).setGraderId(v.graderId().toString()).setSubmissionVersion(v.submissionVersion()).setAssignmentVersion(v.assignmentVersion()).setRubric(rb).setScore(v.score()).setReason(v.reason()).setAudience(v.audience()).setCreatedAt(v.createdAt());
  for(var n:v.criteria())p.addCriteria(RubricScoreV1.newBuilder().setCriterionId(n.criterionId().toString()).setScore(n.score()).setComment(n.comment()));
  var id=ids.next();var at=Instant.parse(v.createdAt());var e=RubricAssessmentEnvelopeV1.newBuilder().setEventId(id.toString()).setEventType("RubricAssessmentRecorded").setEventVersion(1).setOccurredAtEpochMillis(at.toEpochMilli()).setProducer("collaboration-service").setAggregateType("RubricAssessment").setAggregateId(v.assessmentId().toString()).setSpaceId(v.spaceId().toString()).setActorId(v.graderId().toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'rubric-assessment',?,'RubricAssessmentRecorded',?,?,'collaboration','rubric-assessment-events','1',?)",id,v.assessmentId(),Timestamp.from(at),trace,e.toByteArray());
 }
}
