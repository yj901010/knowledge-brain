package io.knowledgebrain.learning;
import java.util.*;import java.time.*;import java.time.temporal.ChronoUnit;import java.sql.Timestamp;
import javax.sql.DataSource;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.learning.roadmapadoption.v1.*;

/** One private, editable learning copy per destination Space and inspected public version. */
final class RoadmapAdoptionStore {
 record Mapping(UUID sourceId,UUID personalId){}
 record Adoption(UUID adoptionId,UUID spaceId,UUID roadmapId,UUID sourceSpaceId,UUID sourceRoadmapId,long sourceVersion,
                 String sourceTitle,UUID actorId,String createdAt,List<Mapping> nodes,List<Mapping> edges){
  Adoption{nodes=List.copyOf(nodes);edges=List.copyOf(edges);}
 }
 record Source(Adoption adoption){}
 record Result(Adoption adoption,boolean created){}
 private final JdbcTemplate sql;private final TransactionTemplate tx;private final PublicRoadmapStore published;
 private final RoadmapStore personal;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 RoadmapAdoptionStore(DataSource ds,PublicRoadmapStore published,RoadmapStore personal,Clock clock){
  sql=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));tx.setTimeout(15);
  this.published=published;this.personal=personal;this.clock=clock;ids=new UuidV7(clock);
 }
 Source source(UUID space,UUID id){
  personal.inspect(space,id);
  var rows=sql.query("SELECT snapshot::text FROM roadmap_adoption WHERE space_id=? AND roadmap_id=?",
    (rs,n)->json.readValue(rs.getString(1),Adoption.class),space,id);
  return new Source(rows.isEmpty()?null:rows.getFirst());
 }
 Result adopt(UUID space,UUID sourceSpace,UUID sourceId,long version,UUID actor,String trace){
  for(var id:List.of(space,sourceSpace,sourceId,actor))RoadmapGraph.id(id);
  if(space.equals(sourceSpace)||version<1||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw RoadmapGraph.bad();
  return tx.execute(s->{
   // Same root lock as public merge. Repeated/lost replies remain recoverable after a new public revision.
   var graph=published.lock(sourceSpace,sourceId);
   var prior=sql.query("SELECT snapshot::text FROM roadmap_adoption WHERE space_id=? AND source_space_id=? AND source_roadmap_id=? AND source_version=?",
     (rs,n)->json.readValue(rs.getString(1),Adoption.class),space,sourceSpace,sourceId,version);
   if(!prior.isEmpty())return new Result(prior.getFirst(),false);
   if(graph.version()!=version)throw PublicRoadmapStore.error(HttpStatus.CONFLICT,"Public roadmap changed; inspect current graph before adopting");
   var refs=new HashMap<UUID,UUID>();var nodes=new ArrayList<RoadmapGraph.Node>();var edges=new ArrayList<RoadmapGraph.Edge>();
   var nodeMap=new ArrayList<Mapping>();var edgeMap=new ArrayList<Mapping>();
   for(var n:graph.nodes()){UUID id=ids.next();refs.put(n.nodeId(),id);nodes.add(new RoadmapGraph.Node(id,n.title(),n.description()));nodeMap.add(new Mapping(n.nodeId(),id));}
   for(var e:graph.edges()){
    UUID id=ids.next(),from=refs.get(e.sourceNodeId()),to=refs.get(e.targetNodeId());
    if(e.kind().equals("RELATED_TO")&&from.toString().compareTo(to.toString())>0){UUID swap=from;from=to;to=swap;}
    edges.add(new RoadmapGraph.Edge(id,e.kind(),from,to));edgeMap.add(new Mapping(e.edgeId(),id));
   }
   nodes.sort(Comparator.comparing(n->n.nodeId().toString()));edges.sort(Comparator.comparing(e->e.edgeId().toString()));
   var copy=personal.adoptGraph(space,actor,graph.title(),graph.description(),nodes,edges,trace);
   var v=new Adoption(ids.next(),space,copy.roadmapId(),sourceSpace,sourceId,version,graph.title(),actor,
     clock.instant().truncatedTo(ChronoUnit.MICROS).toString(),nodeMap,edgeMap);
   sql.update("INSERT INTO roadmap_adoption VALUES (?,?,?,?,?,?,?,?,?::jsonb)",v.adoptionId(),space,v.roadmapId(),sourceSpace,sourceId,version,actor,Timestamp.from(Instant.parse(v.createdAt())),json.writeValueAsString(v));
   event(v,trace);return new Result(v,true);
  });
 }
 private void event(Adoption v,String trace){
  UUID event=ids.next();var p=RoadmapAdoptedV1.newBuilder().setAdoptionId(v.adoptionId().toString()).setRoadmapId(v.roadmapId().toString())
   .setSourceSpaceId(v.sourceSpaceId().toString()).setSourceRoadmapId(v.sourceRoadmapId().toString()).setSourceVersion(v.sourceVersion())
   .setSourceTitle(v.sourceTitle()).setCreatedAt(v.createdAt()).setVisibility("PRIVATE");
  for(var m:v.nodes())p.addNodes(AdoptionIdMappingV1.newBuilder().setSourceId(m.sourceId().toString()).setPersonalId(m.personalId().toString()));
  for(var m:v.edges())p.addEdges(AdoptionIdMappingV1.newBuilder().setSourceId(m.sourceId().toString()).setPersonalId(m.personalId().toString()));
  var now=Instant.parse(v.createdAt());
  var envelope=RoadmapAdoptionEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("RoadmapAdopted").setEventVersion(1)
   .setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("learning-service").setAggregateType("RoadmapAdoption").setAggregateId(v.adoptionId().toString())
   .setSpaceId(v.spaceId().toString()).setActorId(v.actorId().toString()).setTraceId(trace).setPayload(p.build().toByteString()).build();
  sql.update("INSERT INTO outbox_event VALUES (?,'roadmap-adoption',?,?,?,?, 'learning','roadmap-adoption-events','1',?)",event,v.adoptionId(),"RoadmapAdopted",Timestamp.from(now),trace,envelope.toByteArray());
 }
}
