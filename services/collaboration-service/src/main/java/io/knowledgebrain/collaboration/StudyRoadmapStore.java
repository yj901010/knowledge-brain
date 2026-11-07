package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.collaboration.studyroadmap.v1.*;
final class StudyRoadmapStore {
 record Binding(UUID bindingId,UUID studyId,UUID groupId,UUID spaceId,long version,String state,UUID roadmapLinkId,long roadmapLinkVersion,UUID sourceSpaceId,UUID sourceRoadmapId,long sourceVersion,String sourceTitle,String visibility,String createdAt,String updatedAt) {}
 record Current(Binding binding) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Binding snapshot) {}
 record History(List<Revision> items,Long nextBefore) {}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final StudyStore studies;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 StudyRoadmapStore(DataSource ds,StudyStore studies,Clock clock){sql=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);this.studies=studies;this.clock=clock;ids=new UuidV7(clock);}
 private Binding find(UUID group,UUID space,UUID study){var rows=sql.query("SELECT snapshot::text FROM study_roadmap_binding WHERE group_id=? AND space_id=? AND study_id=?",(rs,n)->json.readValue(rs.getString(1),Binding.class),group,space,study);return rows.isEmpty()?null:rows.getFirst();}
 Current get(UUID group,UUID space,UUID study){studies.get(group,space,study);return new Current(find(group,space,study));}
 History history(UUID group,UUID space,UUID study,long before,int limit){var current=get(group,space,study).binding();if(current==null)return new History(List.of(),null);
  var rows=sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM study_roadmap_revision WHERE binding_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Binding.class)),current.bindingId(),before,limit+1);
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new History(items,rows.size()>limit?items.getLast().version():null);
 }
 Current change(UUID group,UUID space,UUID study,UUID actor,long expected,LearningRoadmapClient.Reference source,String trace){
  for(var id:List.of(group,space,study,actor))GroupInput.id(id.toString());if(expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw GroupInput.bad();
  if(source!=null&&(!space.equals(source.spaceId())||!actor.equals(source.principalId())||!"ACTIVE".equals(source.state())||source.version()<1||source.sourceVersion()<1))throw GroupInput.bad();
  return tx.execute(s->{
   studies.lockWritable(group,space,study);var old=find(group,space,study);
   if((old==null?0:old.version())!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Study roadmap binding changed");
   if(source==null&&(old==null||old.state().equals("UNLINKED")))return new Current(old);
   if(source!=null&&old!=null&&old.state().equals("LINKED")&&old.roadmapLinkId().equals(source.roadmapLinkId())&&old.roadmapLinkVersion()==source.version())return new Current(old);
   String now=clock.instant().truncatedTo(ChronoUnit.MICROS).toString();
   var v=new Binding(old==null?ids.next():old.bindingId(),study,group,space,old==null?1:old.version()+1,source==null?"UNLINKED":"LINKED",
    source==null?old.roadmapLinkId():source.roadmapLinkId(),source==null?old.roadmapLinkVersion():source.version(),source==null?old.sourceSpaceId():source.sourceSpaceId(),source==null?old.sourceRoadmapId():source.sourceRoadmapId(),source==null?old.sourceVersion():source.sourceVersion(),source==null?old.sourceTitle():source.title(),"PRIVATE",old==null?now:old.createdAt(),now);
   if(old==null)sql.update("INSERT INTO study_roadmap_binding VALUES (?,?,?,?,?,?::jsonb)",v.bindingId(),study,group,space,v.version(),json.writeValueAsString(v));else sql.update("UPDATE study_roadmap_binding SET version=?,snapshot=?::jsonb WHERE binding_id=?",v.version(),json.writeValueAsString(v),v.bindingId());
   record(v,actor,trace);return new Current(v);
  });
 }
 private void record(Binding v,UUID actor,String trace){
  sql.update("INSERT INTO study_roadmap_revision VALUES (?,?,?,?,?,?::jsonb)",ids.next(),v.bindingId(),v.version(),actor,Timestamp.from(Instant.parse(v.updatedAt())),json.writeValueAsString(v));
  var p=StudyRoadmapSnapshotV1.newBuilder().setBindingId(v.bindingId().toString()).setStudyId(v.studyId().toString()).setGroupId(v.groupId().toString()).setVersion(v.version()).setState(v.state()).setRoadmapLinkId(v.roadmapLinkId().toString()).setRoadmapLinkVersion(v.roadmapLinkVersion()).setSourceSpaceId(v.sourceSpaceId().toString()).setSourceRoadmapId(v.sourceRoadmapId().toString()).setSourceVersion(v.sourceVersion()).setSourceTitle(v.sourceTitle()).setVisibility("PRIVATE").setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  UUID event=ids.next();String type=v.state().equals("LINKED")?"StudyRoadmapLinked":"StudyRoadmapUnlinked";
  var e=StudyRoadmapEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(Instant.parse(v.updatedAt()).toEpochMilli()).setProducer("collaboration-service").setAggregateType("StudyRoadmap").setAggregateId(v.bindingId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'study-roadmap',?,?,?,?, 'collaboration','study-roadmap-events','1',?)",event,v.bindingId(),type,Timestamp.from(Instant.parse(v.updatedAt())),trace,e.toByteArray());
 }
}
