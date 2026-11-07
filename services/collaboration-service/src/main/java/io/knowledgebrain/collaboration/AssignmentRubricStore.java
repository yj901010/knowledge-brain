package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.assignmentrubric.v1.*;
/** Optional Group-shared rubric, immutable after the Assignment first leaves DRAFT. */
final class AssignmentRubricStore {
 record Criterion(UUID criterionId,String title,String description,int maxPoints) {}
 record Rubric(UUID rubricId,UUID assignmentId,UUID studyId,UUID groupId,UUID spaceId,UUID creatorId,UUID actorId,long version,long assignmentVersion,List<Criterion> criteria,String createdAt,String updatedAt) {}
 record View(long assignmentVersion,String assignmentState,Rubric rubric,boolean canWrite) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Rubric snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final AssignmentStore assignments;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 AssignmentRubricStore(DataSource source,AssignmentStore assignments,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.assignments=assignments;this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus status,String reason){return new ResponseStatusException(status,reason);}
 private boolean writable(AssignmentStore.SubmissionParent p){return !p.studyArchived()&&p.assignment().state().equals("DRAFT");}
 private Rubric current(UUID assignment){var rows=sql.query("SELECT snapshot::text FROM assignment_rubric WHERE assignment_id=?",(rs,n)->json.readValue(rs.getString(1),Rubric.class),assignment);return rows.isEmpty()?null:rows.getFirst();}
 private View view(AssignmentStore.SubmissionParent p,Rubric v,boolean owner){return new View(p.assignment().version(),p.assignment().state(),v,owner&&writable(p));}
 View get(UUID group,UUID space,UUID study,UUID assignment,boolean owner){return tx.execute(s->{var p=assignments.submissionParent(group,space,study,assignment,true);return view(p,current(assignment),owner);});}
 View change(UUID group,UUID space,UUID study,UUID assignment,UUID actor,long expected,AssignmentRubricInput input,String trace){
  for(var id:List.of(group,space,study,assignment,actor))GroupInput.id(id.toString());AssignmentRubricInput.validate(input);if(expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  return tx.execute(s->{
   // Shared Group -> Study -> Assignment locks serialize with OPEN/archive/metadata.
   var p=assignments.submissionParent(group,space,study,assignment,true);var old=current(assignment);
   if(expected!=(old==null?0:old.version()))throw error(HttpStatus.PRECONDITION_FAILED,"Rubric changed; refresh");
   if(!writable(p))throw error(HttpStatus.CONFLICT,"Rubric is fixed after DRAFT or Study archive");
   if(input.assignmentVersion()!=p.assignment().version())throw error(HttpStatus.CONFLICT,"Assignment changed; reopen latest definition");
   var existing=old==null?Set.<UUID>of():new HashSet<>(old.criteria().stream().map(Criterion::criterionId).toList());
   if(input.criteria().stream().anyMatch(n->n.criterionId()!=null&&!existing.contains(n.criterionId())))throw error(HttpStatus.CONFLICT,"Criterion is not in the current rubric");
   if(old==null&&input.criteria().isEmpty())return view(p,null,true);
   var criteria=input.criteria().stream().map(n->new Criterion(n.criterionId()==null?ids.next():n.criterionId(),n.title(),n.description(),n.maxPoints())).toList();
   if(old!=null&&old.assignmentVersion()==p.assignment().version()&&old.criteria().equals(criteria))return view(p,old,true);
   String now=clock.instant().truncatedTo(ChronoUnit.MICROS).toString();
   var v=new Rubric(old==null?ids.next():old.rubricId(),assignment,study,group,space,old==null?actor:old.creatorId(),actor,old==null?1:Math.addExact(old.version(),1),p.assignment().version(),criteria,old==null?now:old.createdAt(),now);
   if(old==null)sql.update("INSERT INTO assignment_rubric VALUES (?,?,?,?,?,?,?,?::jsonb)",v.rubricId(),assignment,study,group,space,v.version(),v.assignmentVersion(),json.writeValueAsString(v));
   else sql.update("UPDATE assignment_rubric SET version=?,assignment_version=?,snapshot=?::jsonb WHERE rubric_id=?",v.version(),v.assignmentVersion(),json.writeValueAsString(v),v.rubricId());
   record(v,trace,criteria.isEmpty()?"AssignmentRubricCleared":old==null?"AssignmentRubricDefined":"AssignmentRubricRevised");return view(p,v,true);
  });
 }
 History history(UUID group,UUID space,UUID study,UUID assignment,long before,int limit){
  assignments.submissionParent(group,space,study,assignment,false);var v=current(assignment);if(v==null)return new History(List.of(),null);
  var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM assignment_rubric_revision WHERE rubric_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Rubric.class)),v.rubricId(),before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 private void record(Rubric v,String trace,String type){
  var at=Timestamp.from(Instant.parse(v.updatedAt()));sql.update("INSERT INTO assignment_rubric_revision VALUES (?,?,?,?,?,?,?,?::jsonb)",ids.next(),v.rubricId(),v.version(),v.assignmentId(),v.assignmentVersion(),v.actorId(),at,json.writeValueAsString(v));
  var p=AssignmentRubricSnapshotV1.newBuilder().setRubricId(v.rubricId().toString()).setAssignmentId(v.assignmentId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setCreatorId(v.creatorId().toString()).setActorId(v.actorId().toString()).setVersion(v.version()).setAssignmentVersion(v.assignmentVersion()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  for(var n:v.criteria())p.addCriteria(AssignmentRubricCriterionV1.newBuilder().setCriterionId(n.criterionId().toString()).setTitle(n.title()).setDescription(n.description()).setMaxPoints(n.maxPoints()));
  var id=ids.next();var e=AssignmentRubricEnvelopeV1.newBuilder().setEventId(id.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(at.toInstant().toEpochMilli()).setProducer("collaboration-service").setAggregateType("AssignmentRubric").setAggregateId(v.rubricId().toString()).setSpaceId(v.spaceId().toString()).setActorId(v.actorId().toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'assignment-rubric',?,?,?,?, 'collaboration','assignment-rubric-events','1',?)",id,v.rubricId(),type,at,trace,e.toByteArray());
 }
}
