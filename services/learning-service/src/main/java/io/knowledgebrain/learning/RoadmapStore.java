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
import io.knowledgebrain.contracts.learning.roadmap.v1.*;

/** Learning-only ACID aggregate: metadata, graph, immutable revision and Outbox. */
final class RoadmapStore {
 record Roadmap(UUID roadmapId,UUID spaceId,String visibility,String state,long version,String title,String description,String createdAt,String updatedAt,List<RoadmapGraph.Node> nodes,List<RoadmapGraph.Edge> edges){Roadmap{nodes=List.copyOf(nodes);edges=List.copyOf(edges);}}
 record Summary(UUID roadmapId,String title,long version,String updatedAt){}
 record Page(List<Summary> items,UUID nextAfter){}
 record Revision(UUID revisionId,UUID roadmapId,long version,UUID actorId,String occurredAt,Roadmap snapshot){}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 RoadmapStore(DataSource ds,Clock clock){jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Roadmap unavailable");}
 private static void trace(String trace){if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw RoadmapGraph.bad();}
 Roadmap create(UUID space,UUID actor,String title,String description,String trace){RoadmapGraph.id(space);RoadmapGraph.id(actor);RoadmapGraph.text(title,description,32768);trace(trace);return tx.execute(s->{UUID id=ids.next();var now=clock.instant();jdbc.update("INSERT INTO roadmap VALUES (?,?,'PRIVATE','ACTIVE',1,?,?,?,?)",id,space,title,description,Timestamp.from(now),Timestamp.from(now));var value=new Roadmap(id,space,"PRIVATE","ACTIVE",1,title,description,now.toString(),now.toString(),List.of(),List.of());revision(value,actor,trace,"RoadmapCreated");return value;});}

 Roadmap adoptGraph(UUID space,UUID actor,String title,String description,List<RoadmapGraph.Node> nodes,List<RoadmapGraph.Edge> edges,String trace){
  RoadmapGraph.id(space);RoadmapGraph.id(actor);RoadmapGraph.text(title,description,32768);trace(trace);
  RoadmapGraph.validate(new RoadmapGraph.Edit(nodes.stream().map(n->new RoadmapGraph.NodeEdit(n.nodeId().toString(),n.title(),n.description())).toList(),edges.stream().map(e->new RoadmapGraph.EdgeEdit(e.edgeId().toString(),e.kind(),e.sourceNodeId().toString(),e.targetNodeId().toString())).toList()));
  return tx.execute(s->{UUID id=ids.next();var now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
   jdbc.update("INSERT INTO roadmap VALUES (?,?,'PRIVATE','ACTIVE',1,?,?,?,?)",id,space,title,description,Timestamp.from(now),Timestamp.from(now));
   for(var n:nodes)jdbc.update("INSERT INTO roadmap_node VALUES (?,?,?,?,'ACTIVE')",n.nodeId(),id,n.title(),n.description());
   for(var e:edges)jdbc.update("INSERT INTO roadmap_edge VALUES (?,?,?,?,?,'ACTIVE')",e.edgeId(),id,e.kind(),e.sourceNodeId(),e.targetNodeId());
   var value=new Roadmap(id,space,"PRIVATE","ACTIVE",1,title,description,now.toString(),now.toString(),nodes,edges);
   revision(value,actor,trace,"RoadmapCreated");return value;
  });
 }
 private Roadmap decode(String raw,UUID space,UUID id,long version,String state){if(raw==null)throw new IllegalStateException("Canonical revision unavailable");var value=json.readValue(raw,Roadmap.class);if(!value.roadmapId().equals(id)||!value.spaceId().equals(space)||value.version()!=version||!value.state().equals(state)||!value.visibility().equals("PRIVATE"))throw new IllegalStateException("Canonical revision invalid");return value;}
 private Roadmap current(UUID space,UUID id,boolean lock,boolean active){RoadmapGraph.id(space);RoadmapGraph.id(id);List<Roadmap> rows;
  if(lock){record Head(long version,String state){}var heads=jdbc.query("SELECT version,state FROM roadmap WHERE space_id=? AND roadmap_id=? FOR UPDATE",(rs,n)->new Head(rs.getLong(1),rs.getString(2)),space,id);if(heads.isEmpty()||active&&!heads.getFirst().state().equals("ACTIVE"))throw missing();var head=heads.getFirst();var snapshots=jdbc.query("SELECT snapshot::text FROM roadmap_revision WHERE roadmap_id=? AND version=?",(rs,n)->rs.getString(1),id,head.version());if(snapshots.isEmpty())throw new IllegalStateException("Canonical revision unavailable");rows=List.of(decode(snapshots.getFirst(),space,id,head.version(),head.state()));}
  else rows=jdbc.query("SELECT n.version,n.state,r.snapshot::text FROM roadmap n LEFT JOIN roadmap_revision r ON r.roadmap_id=n.roadmap_id AND r.version=n.version WHERE n.space_id=? AND n.roadmap_id=?",(rs,n)->decode(rs.getString(3),space,id,rs.getLong(1),rs.getString(2)),space,id);
  if(rows.isEmpty()||active&&!rows.getFirst().state().equals("ACTIVE"))throw missing();return rows.getFirst();
 }
 Roadmap get(UUID space,UUID id){return current(space,id,false,true);}
 // Learning-owned related aggregates share this row lock with graph retirement.
 Roadmap lock(UUID space,UUID id){return current(space,id,true,false);}
 Roadmap inspect(UUID space,UUID id){return current(space,id,false,false);}
 private void expected(Roadmap prior,long expected){if(expected<1)throw RoadmapGraph.bad();if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Roadmap changed; inspect latest before saving");}
 Roadmap metadata(UUID space,UUID id,UUID actor,long expected,String title,String description,boolean retire,String trace){RoadmapGraph.id(actor);trace(trace);if(!retire)RoadmapGraph.text(title,description,32768);return tx.execute(s->{var prior=current(space,id,true,true);expected(prior,expected);if(!retire&&prior.title().equals(title)&&prior.description().equals(description))return prior;var value=new Roadmap(id,space,"PRIVATE",retire?"RETIRED":"ACTIVE",Math.addExact(prior.version(),1),retire?prior.title():title,retire?prior.description():description,prior.createdAt(),clock.instant().toString(),prior.nodes(),prior.edges());saveRoot(value);revision(value,actor,trace,retire?"RoadmapRetired":"RoadmapUpdated");return value;});}
 Roadmap graph(UUID space,UUID id,UUID actor,long expected,RoadmapGraph.Edit edit,String trace){RoadmapGraph.id(actor);trace(trace);RoadmapGraph.validate(edit);return tx.execute(s->{var prior=current(space,id,true,true);expected(prior,expected);var activeNodes=new HashSet<UUID>();prior.nodes().forEach(n->activeNodes.add(n.nodeId()));var activeEdges=new HashSet<UUID>();prior.edges().forEach(e->activeEdges.add(e.edgeId()));var references=new HashMap<String,UUID>();var nodes=new ArrayList<RoadmapGraph.Node>();var edges=new ArrayList<RoadmapGraph.Edge>();
  for(var n:edit.nodes()){UUID node=RoadmapGraph.fresh(n.key())?ids.next():RoadmapGraph.id(n.key());if(!RoadmapGraph.fresh(n.key())&&!activeNodes.contains(node))throw RoadmapGraph.bad();references.put(n.key(),node);nodes.add(new RoadmapGraph.Node(node,n.title(),n.description()));}
  for(var e:edit.edges()){UUID edge=RoadmapGraph.fresh(e.key())?ids.next():RoadmapGraph.id(e.key());if(!RoadmapGraph.fresh(e.key())&&!activeEdges.contains(edge))throw RoadmapGraph.bad();UUID source=references.get(e.source()),target=references.get(e.target());if(e.kind().equals("RELATED_TO")&&source.toString().compareTo(target.toString())>0){var swap=source;source=target;target=swap;}edges.add(new RoadmapGraph.Edge(edge,e.kind(),source,target));}
  nodes.sort(Comparator.comparing(n->n.nodeId().toString()));edges.sort(Comparator.comparing(e->e.edgeId().toString()));if(nodes.equals(prior.nodes())&&edges.equals(prior.edges()))return prior;
  // Retire first inside the same transaction, allowing a semantic swap without transient unique conflicts.
  jdbc.update("UPDATE roadmap_edge SET state='RETIRED' WHERE roadmap_id=? AND state='ACTIVE'",id);jdbc.update("UPDATE roadmap_node SET state='RETIRED' WHERE roadmap_id=? AND state='ACTIVE'",id);
  for(var n:nodes)if(activeNodes.contains(n.nodeId()))jdbc.update("UPDATE roadmap_node SET title=?,description=?,state='ACTIVE' WHERE roadmap_id=? AND node_id=?",n.title(),n.description(),id,n.nodeId());else jdbc.update("INSERT INTO roadmap_node VALUES (?,?,?,?,'ACTIVE')",n.nodeId(),id,n.title(),n.description());
  for(var e:edges)if(activeEdges.contains(e.edgeId()))jdbc.update("UPDATE roadmap_edge SET kind=?,source_node_id=?,target_node_id=?,state='ACTIVE' WHERE roadmap_id=? AND edge_id=?",e.kind(),e.sourceNodeId(),e.targetNodeId(),id,e.edgeId());else jdbc.update("INSERT INTO roadmap_edge VALUES (?,?,?,?,?,'ACTIVE')",e.edgeId(),id,e.kind(),e.sourceNodeId(),e.targetNodeId());
  var value=new Roadmap(id,space,"PRIVATE","ACTIVE",Math.addExact(prior.version(),1),prior.title(),prior.description(),prior.createdAt(),clock.instant().toString(),nodes,edges);saveRoot(value);revision(value,actor,trace,"RoadmapGraphChanged");return value;
 });}
 private void saveRoot(Roadmap value){jdbc.update("UPDATE roadmap SET title=?,description=?,state=?,version=?,updated_at=? WHERE roadmap_id=?",value.title(),value.description(),value.state(),value.version(),Timestamp.from(Instant.parse(value.updatedAt())),value.roadmapId());}
 Page list(UUID space,UUID after,int limit){RoadmapGraph.id(space);if(after!=null)RoadmapGraph.id(after);if(limit<1||limit>100)throw RoadmapGraph.bad();var args=after==null?new Object[]{space,limit+1}:new Object[]{space,after,limit+1};var rows=jdbc.query("SELECT roadmap_id,title,version,updated_at FROM roadmap WHERE space_id=? AND state='ACTIVE'"+(after==null?"":" AND roadmap_id>?")+" ORDER BY roadmap_id LIMIT ?",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant().toString()),args);boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));return new Page(page,more?page.getLast().roadmapId():null);}
 List<Revision> history(UUID space,UUID id,long before,int limit){current(space,id,false,false);if(before<1||limit<1||limit>10)throw RoadmapGraph.bad();return jdbc.query("SELECT revision_id,roadmap_id,version,actor_id,occurred_at,snapshot::text FROM roadmap_revision WHERE roadmap_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant().toString(),json.readValue(rs.getString(6),Roadmap.class)),id,before,limit);}
 private void revision(Roadmap value,UUID actor,String trace,String type){UUID revision=ids.next(),event=ids.next();var now=clock.instant();String snapshot=json.writeValueAsString(value);if(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>524288)throw RoadmapGraph.bad();jdbc.update("INSERT INTO roadmap_revision VALUES (?,?,?,?,?,?::jsonb)",revision,value.roadmapId(),value.version(),actor,Timestamp.from(now),snapshot);
  var p=RoadmapChangedPayloadV1.newBuilder().setRoadmapId(value.roadmapId().toString()).setRevisionId(revision.toString()).setVersion(value.version()).setState(value.state()).setVisibility("PRIVATE").setTitle(value.title()).setDescription(value.description());for(var n:value.nodes())p.addNodes(RoadmapNodeV1.newBuilder().setNodeId(n.nodeId().toString()).setTitle(n.title()).setDescription(n.description()));for(var e:value.edges())p.addEdges(RoadmapEdgeV1.newBuilder().setEdgeId(e.edgeId().toString()).setKind(e.kind()).setSourceNodeId(e.sourceNodeId().toString()).setTargetNodeId(e.targetNodeId().toString()));var envelope=RoadmapEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("learning-service").setAggregateType("Roadmap").setAggregateId(value.roadmapId().toString()).setSpaceId(value.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();jdbc.update("INSERT INTO outbox_event VALUES (?,'roadmap',?,?,?,?, 'learning','roadmap-events','1',?)",event,value.roadmapId(),type,Timestamp.from(now),trace,envelope.toByteArray());
 }
}
