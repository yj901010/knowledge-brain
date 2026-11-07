package io.knowledgebrain.learning;

import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.learning.grouproadmap.v1.*;

/** Learning owns explicit Group links to immutable, reviewed public roadmap snapshots. */
final class GroupRoadmapStore {
 record Link(UUID linkId,UUID spaceId,long version,String state,String visibility,
             UUID actorId,String createdAt,String updatedAt,PublicRoadmapStore.Roadmap source) {}
 record Summary(UUID linkId,long version,String state,String title,UUID sourceSpaceId,UUID sourceRoadmapId,long sourceVersion) {}
 record Page(List<Summary> items,UUID nextAfter) {}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Link snapshot) {}
 record Result(Link link,boolean created) {}
 private final JdbcTemplate sql;
 private final TransactionTemplate tx;
 private final PublicRoadmapStore published;
 private final Clock clock;
 private final UuidV7 ids;
 private final JsonMapper json=JsonMapper.builder().build();
 GroupRoadmapStore(DataSource ds,PublicRoadmapStore published,Clock clock){
  sql=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);
  this.published=published;this.clock=clock;ids=new UuidV7(clock);
 }
 private String now(){return clock.instant().truncatedTo(ChronoUnit.MICROS).toString();}
 private void validate(UUID space,UUID actor,String trace){
  RoadmapGraph.id(space);RoadmapGraph.id(actor);
  if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw RoadmapGraph.bad();
 }
 Link get(UUID space,UUID link){return get(space,link,false);}
 private Link get(UUID space,UUID link,boolean lock){
  var rows=sql.query("SELECT snapshot::text FROM group_roadmap_link WHERE space_id=? AND link_id=?"+(lock?" FOR UPDATE":""),
   (rs,n)->json.readValue(rs.getString(1),Link.class),space,link);
  if(rows.isEmpty())throw PublicRoadmapStore.error(HttpStatus.NOT_FOUND,"Group roadmap unavailable");return rows.getFirst();
 }
 Page list(UUID space,UUID after,int limit){
  var rows=sql.query("SELECT link_id,version,state,title,source_space_id,source_roadmap_id,source_version FROM group_roadmap_link WHERE space_id=?"+
   (after==null?"":" AND link_id>?")+" ORDER BY link_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),rs.getLong(7)),
   after==null?new Object[]{space,limit+1}:new Object[]{space,after,limit+1});
  var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().linkId():null);
 }
 List<Revision> history(UUID space,UUID link,long before,int limit){
  get(space,link);return sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM group_roadmap_link_revision WHERE link_id=? AND version<? ORDER BY version DESC LIMIT ?",
   (rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Link.class)),link,before,limit);
 }
 Result attach(UUID space,UUID sourceSpace,UUID sourceId,long version,UUID actor,String trace){
  validate(space,actor,trace);RoadmapGraph.id(sourceSpace);RoadmapGraph.id(sourceId);
  if(version<1||space.equals(sourceSpace))throw RoadmapGraph.bad();
  return tx.execute(s->{
   // Same root lock as public merge and other attachments; no distributed lock/transaction.
   var source=published.lock(sourceSpace,sourceId);
   var prior=sql.query("SELECT snapshot::text FROM group_roadmap_link WHERE space_id=? AND source_space_id=? AND source_roadmap_id=? AND source_version=?",
    (rs,n)->json.readValue(rs.getString(1),Link.class),space,sourceSpace,sourceId,version);
   if(!prior.isEmpty())return new Result(prior.getFirst(),false); // Also after retirement: never restore on retry.
   if(source.version()!=version)throw PublicRoadmapStore.error(HttpStatus.CONFLICT,"Public roadmap changed; inspect before linking");
   String now=now();var link=new Link(ids.next(),space,1,"ACTIVE","PRIVATE",actor,now,now,source);
   sql.update("INSERT INTO group_roadmap_link VALUES (?,?,?,?,?,1,'ACTIVE',?,?::jsonb)",link.linkId(),space,sourceSpace,sourceId,version,source.title(),json.writeValueAsString(link));
   revision(link,actor,trace);return new Result(link,true);
  });
 }
 Link retire(UUID space,UUID id,long expected,UUID actor,String trace){
  validate(space,actor,trace);RoadmapGraph.id(id);if(expected<1)throw RoadmapGraph.bad();
  return tx.execute(s->{
   var old=get(space,id,true);
   if(old.version()!=expected)throw PublicRoadmapStore.error(HttpStatus.PRECONDITION_FAILED,"Group roadmap changed");
   if(old.state().equals("RETIRED"))return old;
   var link=new Link(id,space,Math.addExact(old.version(),1),"RETIRED","PRIVATE",old.actorId(),old.createdAt(),now(),old.source());
   sql.update("UPDATE group_roadmap_link SET version=?,state='RETIRED',snapshot=?::jsonb WHERE link_id=?",link.version(),json.writeValueAsString(link),id);
   revision(link,actor,trace);return link;
  });
 }
 private void revision(Link v,UUID actor,String trace){
  sql.update("INSERT INTO group_roadmap_link_revision VALUES (?,?,?,?,?,?::jsonb)",ids.next(),v.linkId(),v.version(),actor,Timestamp.from(Instant.parse(v.updatedAt())),json.writeValueAsString(v));
  var g=v.source();var p=GroupRoadmapChangedV1.newBuilder().setLinkId(v.linkId().toString()).setVersion(v.version()).setState(v.state()).setVisibility("PRIVATE")
   .setSourceSpaceId(g.spaceId().toString()).setSourceRoadmapId(g.roadmapId().toString()).setSourceVersion(g.version())
   .setTitle(g.title()).setDescription(g.description()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());
  g.nodes().forEach(n->p.addNodes(GroupRoadmapNodeV1.newBuilder().setNodeId(n.nodeId().toString()).setTitle(n.title()).setDescription(n.description())));
  g.edges().forEach(e->p.addEdges(GroupRoadmapEdgeV1.newBuilder().setEdgeId(e.edgeId().toString()).setKind(e.kind()).setSourceNodeId(e.sourceNodeId().toString()).setTargetNodeId(e.targetNodeId().toString())));
  UUID event=ids.next();String type=v.version()==1?"GroupRoadmapLinked":"GroupRoadmapRetired";
  var envelope=GroupRoadmapEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(Instant.parse(v.updatedAt()).toEpochMilli())
   .setProducer("learning-service").setAggregateType("GroupRoadmap").setAggregateId(v.linkId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'group-roadmap',?,?,?,?, 'learning','group-roadmap-events','1',?)",event,v.linkId(),type,Timestamp.from(Instant.parse(v.updatedAt())),trace,envelope.toByteArray());
 }
}
