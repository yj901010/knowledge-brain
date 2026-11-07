package io.knowledgebrain.collaboration;

import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.course.v1.*;

/** Group-owned ordered Study curriculum. Personal progress is never copied. */
final class CourseStore {
 record StudyReference(UUID studyId,long studyVersion,String title) {}
 record Course(UUID courseId,UUID groupId,UUID spaceId,UUID creatorId,long version,String title,String description,String state,String visibility,List<StudyReference> studies,String createdAt,String updatedAt) {}
 record Summary(UUID courseId,long version,String title,String state) {}
 record Page(List<Summary> items,UUID nextAfter) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Course snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 record Result(Course course,boolean created) {}
 private final JdbcTemplate sql;
 private final TransactionTemplate tx;
 private final Clock clock;
 private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 CourseStore(DataSource source,Clock clock){sql=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException error(HttpStatus status,String reason){return new ResponseStatusException(status,reason);}
 private String now(){return clock.instant().truncatedTo(ChronoUnit.MICROS).toString();}
 private void validate(UUID group,UUID space,UUID actor,String trace){
  for(var id:List.of(group,space,actor))GroupInput.id(id.toString());
  if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
 }
 private void group(UUID group,UUID space,boolean lock){
  var rows=sql.query("SELECT group_id FROM collaboration_group WHERE group_id=? AND space_id=? AND state='ACTIVE'"+(lock?" FOR UPDATE":""),(rs,n)->rs.getObject(1,UUID.class),group,space);
  if(rows.size()!=1)throw error(HttpStatus.NOT_FOUND,"Group unavailable");
 }
 Course lockForChild(UUID group,UUID space,UUID course){group(group,space,true);return get(group,space,course,true);}
 Course lockWritable(UUID group,UUID space,UUID course){var v=lockForChild(group,space,course);if(v.state().equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Course is archived");return v;}
 Course get(UUID group,UUID space,UUID course){return get(group,space,course,false);}
 private Course get(UUID group,UUID space,UUID course,boolean lock){
  var rows=sql.query("SELECT snapshot::text FROM group_course WHERE group_id=? AND space_id=? AND course_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Course.class),group,space,course);
  if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Course unavailable");return rows.getFirst();
 }
 Page list(UUID group,UUID space,UUID after,int limit){
  group(group,space,false);var rows=sql.query("SELECT course_id,version,title,state FROM group_course WHERE group_id=? AND space_id=?"+(after==null?"":" AND course_id>?")+" ORDER BY course_id LIMIT ?",
   (rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4)),after==null?new Object[]{group,space,limit+1}:new Object[]{group,space,after,limit+1});
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().courseId():null);
 }
 History history(UUID group,UUID space,UUID course,long before,int limit){
  get(group,space,course);var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM group_course_revision WHERE course_id=? AND version<? ORDER BY version DESC LIMIT ?",
   (rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Course.class)),course,before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 Result create(UUID group,UUID space,UUID actor,String key,String title,String description,String trace){
  validate(group,space,actor,trace);settings(title,description);
  if(key==null||!key.matches("[0-9a-f]{32}"))throw GroupInput.bad();
  return tx.execute(s->{
   group(group,space,true);var prior=sql.query("SELECT creation_title,creation_description,snapshot::text FROM group_course WHERE group_id=? AND creator_id=? AND request_key=?",(rs,n)->{
    if(!rs.getString(1).equals(title)||!rs.getString(2).equals(description))throw error(HttpStatus.CONFLICT,"Course creation key already used");return json.readValue(rs.getString(3),Course.class);
   },group,actor,key);
   if(!prior.isEmpty())return new Result(prior.getFirst(),false);
   String now=now();var v=new Course(ids.next(),group,space,actor,1,title,description,"DRAFT","PRIVATE",List.of(),now,now);
   sql.update("INSERT INTO group_course VALUES (?,?,?,?,?,?,?,1,'DRAFT',?,?::jsonb)",v.courseId(),group,space,actor,key,title,description,title,json.writeValueAsString(v));
   record(v,actor,trace,"CourseCreated");return new Result(v,true);
  });
 }
 private void settings(String title,String description){
  if(title==null||description==null)throw GroupInput.bad();
  GroupInput.settings(json.writeValueAsBytes(Map.of("title",title,"description",description)));
 }
 Course metadata(UUID group,UUID space,UUID id,UUID actor,long expected,String title,String description,String trace){settings(title,description);return change(group,space,id,actor,expected,title,description,null,trace);}
 Course state(UUID group,UUID space,UUID id,UUID actor,long expected,String target,String trace){
  if(target==null||!Set.of("DRAFT","ACTIVE","COMPLETED","RETIRED").contains(target))throw GroupInput.bad();return change(group,space,id,actor,expected,null,null,target,trace);
 }
 private Course change(UUID group,UUID space,UUID id,UUID actor,long expected,String title,String description,String target,String trace){
  validate(group,space,actor,trace);GroupInput.id(id.toString());if(expected<1)throw GroupInput.bad();
  return tx.execute(s->{
   group(group,space,true);var old=get(group,space,id,true);
   if(old.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Course changed; refresh current version");
   if(target!=null&&old.state().equals(target))return old;
   if(old.state().equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Course is archived");
   if(target!=null&&!target.equals("RETIRED")&&!(old.state().equals("DRAFT")&&target.equals("ACTIVE")||old.state().equals("ACTIVE")&&target.equals("COMPLETED")))throw error(HttpStatus.CONFLICT,"Invalid Course lifecycle transition");
   if(target==null&&old.title().equals(title)&&old.description().equals(description))return old;
   if("ACTIVE".equals(target)){if(old.studies().isEmpty())throw error(HttpStatus.CONFLICT,"Course needs a curriculum");resolve(group,space,old.studies().stream().map(StudyReference::studyId).toList());}
   var v=new Course(id,group,space,old.creatorId(),Math.addExact(old.version(),1),target==null?title:old.title(),target==null?description:old.description(),target==null?old.state():target,"PRIVATE",old.studies(),old.createdAt(),now());
   sql.update("UPDATE group_course SET version=?,state=?,title=?,snapshot=?::jsonb WHERE course_id=?",v.version(),v.state(),v.title(),json.writeValueAsString(v),id);
   record(v,actor,trace,target==null?"CourseRevised":target.equals("RETIRED")?"CourseRetired":"CourseStateChanged");return v;
  });
 }
 static List<UUID> curriculum(byte[] bytes){var node=GroupInput.json(bytes);if(node==null||!node.isObject()||node.size()!=1||!node.path("studyIds").isArray())throw GroupInput.bad();var ids=new ArrayList<UUID>();for(var v:node.path("studyIds")){if(!v.isString())throw GroupInput.bad();ids.add(GroupInput.id(v.asString()));}curriculum(ids);return List.copyOf(ids);}
 private static void curriculum(List<UUID> ids){if(ids==null||ids.size()>50||new HashSet<>(ids).size()!=ids.size())throw GroupInput.bad();for(var id:ids){if(id==null)throw GroupInput.bad();GroupInput.id(id.toString());}}
 private List<StudyReference> resolve(UUID group,UUID space,List<UUID> ids){
  curriculum(ids);if(ids.isEmpty())return List.of();var args=new ArrayList<Object>();args.add(group);args.add(space);args.addAll(ids);
  var refs=new HashMap<UUID,StudyReference>();sql.query("SELECT study_id,version,title,state FROM group_study WHERE group_id=? AND space_id=? AND study_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") ORDER BY study_id FOR UPDATE",rs->{if(rs.getString(4).equals("RETIRED"))throw error(HttpStatus.CONFLICT,"Archived Study cannot be selected or started");var id=rs.getObject(1,UUID.class);refs.put(id,new StudyReference(id,rs.getLong(2),rs.getString(3)));},args.toArray());
  if(refs.size()!=ids.size())throw error(HttpStatus.NOT_FOUND,"Study unavailable in this Group");return ids.stream().map(refs::get).toList();
 }
 Course curriculum(UUID group,UUID space,UUID id,UUID actor,long expected,List<UUID> studyIds,String trace){
  validate(group,space,actor,trace);GroupInput.id(id.toString());curriculum(studyIds);if(expected<1)throw GroupInput.bad();
  return tx.execute(s->{group(group,space,true);var old=get(group,space,id,true);if(old.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Course changed; refresh current version");if(!old.state().equals("DRAFT"))throw error(HttpStatus.CONFLICT,"Started or archived curriculum is fixed");var refs=resolve(group,space,studyIds);if(old.studies().equals(refs))return old;
   var v=new Course(id,group,space,old.creatorId(),Math.addExact(old.version(),1),old.title(),old.description(),old.state(),"PRIVATE",refs,old.createdAt(),now());
   sql.update("DELETE FROM course_study WHERE course_id=?",id);for(int i=0;i<refs.size();i++){var ref=refs.get(i);sql.update("INSERT INTO course_study VALUES (?,?,?,?,?,?)",id,group,space,i,ref.studyId(),ref.studyVersion());}
   sql.update("UPDATE group_course SET version=?,snapshot=?::jsonb WHERE course_id=?",v.version(),json.writeValueAsString(v),id);record(v,actor,trace,"CourseCurriculumChanged");return v;
  });
 }
 private void record(Course v,UUID actor,String trace,String type){
  sql.update("INSERT INTO group_course_revision VALUES (?,?,?,?,?,?::jsonb)",ids.next(),v.courseId(),v.version(),actor,Timestamp.from(Instant.parse(v.updatedAt())),json.writeValueAsString(v));
  var p=CourseSnapshotV1.newBuilder().setCourseId(v.courseId().toString()).setGroupId(v.groupId().toString()).setSpaceId(v.spaceId().toString()).setCreatorId(v.creatorId().toString())
   .setVersion(v.version()).setTitle(v.title()).setDescription(v.description()).setState(v.state()).setVisibility("PRIVATE").setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  for(var ref:v.studies())p.addStudies(CourseStudyReferenceV1.newBuilder().setStudyId(ref.studyId().toString()).setStudyVersion(ref.studyVersion()).setTitle(ref.title()));
  UUID event=ids.next();var e=CourseEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(Instant.parse(v.updatedAt()).toEpochMilli())
   .setProducer("collaboration-service").setAggregateType("Course").setAggregateId(v.courseId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'course',?,?,?,?, 'collaboration','course-events','1',?)",event,v.courseId(),type,Timestamp.from(Instant.parse(v.updatedAt())),trace,e.toByteArray());
 }
}
