package io.knowledgebrain.learning;
import java.util.*;
import java.time.*;
import java.sql.Timestamp;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.learning.publicroadmap.v1.*;

/** Learning-owned explicit public snapshots. Never reads private roadmap/study/evidence tables. */
final class PublicRoadmapStore {
 record Roadmap(UUID roadmapId,UUID spaceId,String visibility,String verification,long version,String title,String description,String createdAt,String updatedAt,List<RoadmapGraph.Node> nodes,List<RoadmapGraph.Edge> edges){}
 record Summary(UUID roadmapId,String title,long version,String updatedAt){}
 record Page(List<Summary> items,UUID nextAfter){}
 record Revision(UUID revisionId,long version,UUID actorId,String occurredAt,Roadmap snapshot){}
 record Proposal(UUID contributionId,UUID spaceId,UUID roadmapId,long baseVersion,UUID authorId,String state,long version,String title,String description,String rationale,RoadmapGraph.Graph graph,String createdAt,String updatedAt,Long mergedRoadmapVersion){}
 record ProposalSummary(UUID contributionId,UUID roadmapId,long baseVersion,UUID authorId,String state,long version,String title){}
 record Proposals(List<ProposalSummary> items,UUID nextAfter){}
 record Review(UUID reviewId,long contributionVersion,UUID reviewerId,String decision,String reason,String occurredAt){}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 PublicRoadmapStore(DataSource ds,Clock clock){sql=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 static ResponseStatusException error(HttpStatus status,String text){return new ResponseStatusException(status,text);}
 static void reason(String value){RoadmapGraph.text("reason",value,8000);if(value.isBlank()||value.codePointCount(0,value.length())>2000)throw RoadmapGraph.bad();}
 private void trace(String trace){if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw RoadmapGraph.bad();}
 private String snapshot(Object value){String raw=json.writeValueAsString(value);if(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>524288)throw RoadmapGraph.bad();return raw;}
 Roadmap get(UUID space,UUID id){return get(space,id,false);}
 Roadmap lock(UUID space,UUID id){return get(space,id,true);}
 private Roadmap get(UUID space,UUID id,boolean lock){var rows=sql.query("SELECT snapshot::text FROM public_roadmap WHERE space_id=? AND roadmap_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Roadmap.class),space,id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Public roadmap unavailable");return rows.getFirst();}
 Page list(UUID space,UUID after,int limit){var rows=sql.query("SELECT roadmap_id,title,version,updated_at FROM public_roadmap WHERE space_id=?"+(after==null?"":" AND roadmap_id>?")+" ORDER BY roadmap_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant().toString()),after==null?new Object[]{space,limit+1}:new Object[]{space,after,limit+1});var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().roadmapId():null);}
 List<Revision> history(UUID space,UUID id,long before,int limit){get(space,id);return sql.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM public_roadmap_revision WHERE roadmap_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Roadmap.class)),id,before,limit);}
 Proposal proposal(UUID space,UUID id){return proposal(space,id,false);}
 private Proposal proposal(UUID space,UUID id,boolean lock){var rows=sql.query("SELECT * FROM roadmap_contribution WHERE space_id=? AND contribution_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Proposal(rs.getObject("contribution_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getObject("roadmap_id",UUID.class),rs.getLong("base_version"),rs.getObject("author_id",UUID.class),rs.getString("state"),rs.getLong("version"),rs.getString("title"),rs.getString("description"),rs.getString("rationale"),json.readValue(rs.getString("graph"),RoadmapGraph.Graph.class),rs.getTimestamp("created_at").toInstant().toString(),rs.getTimestamp("updated_at").toInstant().toString(),rs.getObject("merged_roadmap_version",Long.class)),space,id);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Roadmap contribution unavailable");return rows.getFirst();}
 Proposals proposals(UUID space,UUID after,int limit){var rows=sql.query("SELECT contribution_id,roadmap_id,base_version,author_id,state,version,title FROM roadmap_contribution WHERE space_id=?"+(after==null?"":" AND contribution_id>?")+" ORDER BY contribution_id LIMIT ?",(rs,n)->new ProposalSummary(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getLong(6),rs.getString(7)),after==null?new Object[]{space,limit+1}:new Object[]{space,after,limit+1});var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Proposals(items,rows.size()>limit?items.getLast().contributionId():null);}
 List<Review> reviews(UUID space,UUID id){proposal(space,id);return sql.query("SELECT review_id,contribution_version,reviewer_id,decision,reason,occurred_at FROM roadmap_contribution_review WHERE contribution_id=? ORDER BY contribution_version",(rs,n)->new Review(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant().toString()),id);}
 Proposal submit(UUID space,UUID actor,UUID target,long base,String title,String description,String rationale,RoadmapGraph.Edit edit,String trace){
  RoadmapGraph.id(space);RoadmapGraph.id(actor);RoadmapGraph.text(title,description,32768);reason(rationale);trace(trace);RoadmapGraph.validate(edit);
  if(target==null&&base!=0||target!=null&&base<1)throw RoadmapGraph.bad();if(target!=null)RoadmapGraph.id(target);
  return tx.execute(s->{Roadmap prior=target==null?null:get(space,target,true);if(prior!=null&&prior.version()!=base)throw error(HttpStatus.PRECONDITION_FAILED,"Public roadmap changed");
   var graph=materialize(edit,prior);UUID id=ids.next(),roadmap=target==null?ids.next():target;String now=clock.instant().toString();
   var p=new Proposal(id,space,roadmap,base,actor,"SUBMITTED",1,title,description,rationale,graph,now,now,null);snapshot(p);
   sql.update("INSERT INTO roadmap_contribution VALUES (?,?,?,?,?,'SUBMITTED',1,?,?,?,?::jsonb,?,?,NULL)",id,space,roadmap,base,actor,title,description,rationale,json.writeValueAsString(graph),Timestamp.from(Instant.parse(now)),Timestamp.from(Instant.parse(now)));
   contributionEvent(p,actor,trace,null,"");return p;
  });
 }
 private RoadmapGraph.Graph materialize(RoadmapGraph.Edit edit,Roadmap prior){
  var allowedNodes=new HashSet<UUID>();var allowedEdges=new HashSet<UUID>();if(prior!=null){prior.nodes().forEach(n->allowedNodes.add(n.nodeId()));prior.edges().forEach(e->allowedEdges.add(e.edgeId()));}
  var refs=new HashMap<String,UUID>();var nodes=new ArrayList<RoadmapGraph.Node>();var edges=new ArrayList<RoadmapGraph.Edge>();
  for(var n:edit.nodes()){UUID id=RoadmapGraph.fresh(n.key())?ids.next():RoadmapGraph.id(n.key());if(!RoadmapGraph.fresh(n.key())&&!allowedNodes.contains(id))throw RoadmapGraph.bad();refs.put(n.key(),id);nodes.add(new RoadmapGraph.Node(id,n.title(),n.description()));}
  for(var e:edit.edges()){UUID id=RoadmapGraph.fresh(e.key())?ids.next():RoadmapGraph.id(e.key());if(!RoadmapGraph.fresh(e.key())&&!allowedEdges.contains(id))throw RoadmapGraph.bad();UUID source=refs.get(e.source()),target=refs.get(e.target());if(e.kind().equals("RELATED_TO")&&source.toString().compareTo(target.toString())>0){var swap=source;source=target;target=swap;}edges.add(new RoadmapGraph.Edge(id,e.kind(),source,target));}
  nodes.sort(Comparator.comparing(n->n.nodeId().toString()));edges.sort(Comparator.comparing(e->e.edgeId().toString()));return new RoadmapGraph.Graph(nodes,edges);
 }
 Proposal review(UUID space,UUID id,UUID actor,long expected,String decision,String reason,String trace){if(decision==null||!Set.of("APPROVED","REJECTED").contains(decision))throw RoadmapGraph.bad();reason(reason);return transition(space,id,actor,expected,decision,reason,trace);}
 Proposal merge(UUID space,UUID id,UUID actor,long expected,String trace){return transition(space,id,actor,expected,"MERGED","",trace);}
 private Proposal transition(UUID space,UUID id,UUID actor,long expected,String state,String reason,String trace){
  RoadmapGraph.id(space);RoadmapGraph.id(id);RoadmapGraph.id(actor);trace(trace);if(expected<1)throw RoadmapGraph.bad();
  return tx.execute(s->{var prior=proposal(space,id,true);if(actor.equals(prior.authorId()))throw error(HttpStatus.FORBIDDEN,"Self-review and self-merge forbidden");if(prior.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Contribution changed");if(!prior.state().equals(state.equals("MERGED")?"APPROVED":"SUBMITTED"))throw error(HttpStatus.CONFLICT,"Invalid contribution transition");
   Long merged=null;String now=clock.instant().toString();if(state.equals("MERGED")){
    Roadmap canonical=prior.baseVersion()==0?null:get(space,prior.roadmapId(),true);if(canonical!=null&&canonical.version()!=prior.baseVersion())throw error(HttpStatus.PRECONDITION_FAILED,"Public roadmap changed; new proposal required");
    merged=Math.addExact(prior.baseVersion(),1);var value=new Roadmap(prior.roadmapId(),space,"PUBLIC","REVIEWED",merged,prior.title(),prior.description(),canonical==null?now:canonical.createdAt(),now,prior.graph().nodes(),prior.graph().edges());String raw=snapshot(value);
    if(canonical==null)sql.update("INSERT INTO public_roadmap VALUES (?,?,?,?,?,?::jsonb)",value.roadmapId(),space,merged,value.title(),Timestamp.from(Instant.parse(now)),raw);else sql.update("UPDATE public_roadmap SET version=?,title=?,updated_at=?,snapshot=?::jsonb WHERE roadmap_id=?",merged,value.title(),Timestamp.from(Instant.parse(now)),raw,value.roadmapId());
    UUID revision=ids.next();sql.update("INSERT INTO public_roadmap_revision VALUES (?,?,?,?,?,?::jsonb)",revision,value.roadmapId(),merged,actor,Timestamp.from(Instant.parse(now)),raw);canonicalEvent(value,revision,actor,trace);
   }
   UUID review=ids.next();sql.update("UPDATE roadmap_contribution SET state=?,version=version+1,updated_at=?,merged_roadmap_version=? WHERE contribution_id=?",state,Timestamp.from(Instant.parse(now)),merged,id);
   sql.update("INSERT INTO roadmap_contribution_review VALUES (?,?,?,?,?,?,?)",review,id,prior.version()+1,actor,state,reason,Timestamp.from(Instant.parse(now)));
   var value=proposal(space,id);contributionEvent(value,actor,trace,review,reason);return value;
  });
 }
 private PublicRoadmapNodeV1 node(RoadmapGraph.Node n){return PublicRoadmapNodeV1.newBuilder().setNodeId(n.nodeId().toString()).setTitle(n.title()).setDescription(n.description()).build();}
 private PublicRoadmapEdgeV1 edge(RoadmapGraph.Edge e){return PublicRoadmapEdgeV1.newBuilder().setEdgeId(e.edgeId().toString()).setKind(e.kind()).setSourceNodeId(e.sourceNodeId().toString()).setTargetNodeId(e.targetNodeId().toString()).build();}
 private void canonicalEvent(Roadmap v,UUID revision,UUID actor,String trace){var p=PublicRoadmapSnapshotV1.newBuilder().setRoadmapId(v.roadmapId().toString()).setVersion(v.version()).setVisibility("PUBLIC").setVerification("REVIEWED").setTitle(v.title()).setDescription(v.description()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt());v.nodes().forEach(n->p.addNodes(node(n)));v.edges().forEach(e->p.addEdges(edge(e)));event("public-roadmap","PublicRoadmap",v.version()==1?"PublicRoadmapPublished":"PublicRoadmapRevised",v.roadmapId(),v.spaceId(),actor,trace,PublicRoadmapChangedV1.newBuilder().setRevisionId(revision.toString()).setSnapshot(p).build().toByteString());}
 private void contributionEvent(Proposal v,UUID actor,String trace,UUID review,String reason){var p=RoadmapContributionChangedV1.newBuilder().setContributionId(v.contributionId().toString()).setRoadmapId(v.roadmapId().toString()).setBaseVersion(v.baseVersion()).setVersion(v.version()).setState(v.state()).setAuthorId(v.authorId().toString()).setTitle(v.title()).setDescription(v.description()).setRationale(v.rationale()).setReviewId(review==null?"":review.toString()).setReviewReason(reason).setMergedRoadmapVersion(v.mergedRoadmapVersion()==null?0:v.mergedRoadmapVersion());v.graph().nodes().forEach(n->p.addNodes(node(n)));v.graph().edges().forEach(e->p.addEdges(edge(e)));event("roadmap-contribution","RoadmapContribution","RoadmapContribution"+switch(v.state()){case "SUBMITTED"->"Submitted";case "APPROVED"->"Approved";case "REJECTED"->"Rejected";case "MERGED"->"Merged";default->throw new IllegalStateException();},v.contributionId(),v.spaceId(),actor,trace,p.build().toByteString());}
 private void event(String topic,String aggregate,String type,UUID id,UUID space,UUID actor,String trace,ByteString payload){UUID event=ids.next();var now=clock.instant();var envelope=PublicRoadmapEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("learning-service").setAggregateType(aggregate).setAggregateId(id.toString()).setSpaceId(space.toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload).build();sql.update("INSERT INTO outbox_event VALUES (?,?,?,?,?,?,'learning',?,'1',?)",event,topic,id,type,Timestamp.from(now),trace,topic+"-events",envelope.toByteArray());}
}
