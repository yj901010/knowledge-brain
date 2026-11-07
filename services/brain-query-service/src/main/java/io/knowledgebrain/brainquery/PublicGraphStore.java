package io.knowledgebrain.brainquery;
import java.util.*;
import org.janusgraph.core.*;
import org.janusgraph.core.schema.*;
import org.apache.tinkerpop.gremlin.structure.*;

final class PublicGraphStore implements AutoCloseable {
 private final JanusGraph graph;PublicGraphStore(JanusGraph graph){this.graph=graph;}
 static void initialize(JanusGraph graph){var m=graph.openManagement();try{
  if(m.getVertexLabel("pubEntity")==null)m.makeVertexLabel("pubEntity").make();
  for(String key:List.of("pubId","pubSpace","pubKind","pubTitle","pubHash","pubRole"))if(m.getPropertyKey(key)==null)m.makePropertyKey(key).dataType(String.class).make();
  if(m.getPropertyKey("pubVisible")==null)m.makePropertyKey("pubVisible").dataType(Boolean.class).make();
  for(String key:List.of("pubSequence","pubVersion"))if(m.getPropertyKey(key)==null){var p=m.makePropertyKey(key).dataType(Long.class).make();m.setConsistency(p,ConsistencyModifier.LOCK);}
  if(m.getEdgeLabel("pubRelation")==null)m.makeEdgeLabel("pubRelation").multiplicity(Multiplicity.MULTI).make();
  if(m.getGraphIndex("pubEntityById")==null){var i=m.buildIndex("pubEntityById",Vertex.class).addKey(m.getPropertyKey("pubId")).unique().buildCompositeIndex();m.setConsistency(i,ConsistencyModifier.LOCK);}
  if(m.getGraphIndex("pubVisibleBySpace")==null)m.buildIndex("pubVisibleBySpace",Vertex.class).addKey(m.getPropertyKey("pubSpace")).addKey(m.getPropertyKey("pubVisible")).buildCompositeIndex();
  if(m.getGraphIndex("pubByKind")==null)m.buildIndex("pubByKind",Vertex.class).addKey(m.getPropertyKey("pubKind")).buildCompositeIndex();
  m.commit();
 }catch(RuntimeException e){if(m.isOpen())m.rollback();throw e;}}
 private Vertex vertex(JanusGraphTransaction tx,UUID id,UUID space){var it=tx.query().has("pubId",id.toString()).vertices().iterator();if(it.hasNext()){var v=it.next();if(it.hasNext()||!v.value("pubSpace").equals(space.toString()))throw new PublicProjectionStore.Conflict();return v;}return tx.addVertex(T.label,"pubEntity","pubId",id.toString(),"pubSpace",space.toString(),"pubKind","UNKNOWN","pubTitle","","pubVisible",false,"pubSequence",0L,"pubVersion",0L,"pubHash","");}
 void apply(PublicProjection.Document d){var tx=graph.newTransaction();try{var v=vertex(tx,d.id(),d.space());long old=v.value("pubSequence");if(old>d.sequence()){tx.rollback();return;}if(old==d.sequence()){if(!v.value("pubHash").equals(d.hash()))throw new PublicProjectionStore.Conflict();tx.rollback();return;}var edges=v.edges(Direction.OUT,"pubRelation");while(edges.hasNext())edges.next().remove();v.property("pubKind",d.kind());v.property("pubTitle",d.title());v.property("pubVisible",d.visible());v.property("pubVersion",d.canonicalVersion());v.property("pubSequence",d.sequence());v.property("pubHash",d.hash());for(var ref:d.refs())v.addEdge("pubRelation",vertex(tx,ref.target(),d.space()),"pubRole",ref.role());tx.commit();}catch(RuntimeException e){if(tx.isOpen())tx.rollback();throw e;}}
 record Node(UUID id,String kind,String title,long version){}
 record Edge(String id,UUID source,UUID target,String role){}
 record View(List<Node> nodes,List<Edge> edges,boolean truncated){}
 View read(UUID space,UUID center,int depth,int limit){if(depth<1||depth>3||limit<1||limit>100)throw PublicProjection.invalid();var tx=graph.newTransaction();try{
  var selected=new LinkedHashMap<String,Vertex>();var frontier=new ArrayList<Vertex>();boolean truncated=false;
  if(center==null){for(var v:tx.query().has("pubSpace",space.toString()).has("pubVisible",true).limit(limit+1).vertices()){if(selected.size()==limit){truncated=true;break;}selected.put(v.value("pubId"),v);}}
  else{var it=tx.query().has("pubId",center.toString()).has("pubSpace",space.toString()).has("pubVisible",true).vertices().iterator();if(it.hasNext()){var v=it.next();selected.put(center.toString(),v);frontier.add(v);}for(int step=0;step<depth&&!frontier.isEmpty();step++){var next=new ArrayList<Vertex>();for(var v:frontier){var edges=v.edges(Direction.BOTH,"pubRelation");int visited=0;while(edges.hasNext()){if(++visited>1000){truncated=true;break;}var e=edges.next();var other=e.outVertex().equals(v)?e.inVertex():e.outVertex();if(!other.value("pubSpace").equals(space.toString())||!Boolean.TRUE.equals(other.value("pubVisible")))continue;String key=other.value("pubId");if(selected.containsKey(key))continue;if(selected.size()==limit){truncated=true;continue;}selected.put(key,other);next.add(other);}}frontier=next;}}
  var nodes=new ArrayList<Node>();var edges=new ArrayList<Edge>();for(var entry:selected.entrySet()){var v=entry.getValue();nodes.add(new Node(UUID.fromString(entry.getKey()),v.value("pubKind"),v.value("pubTitle"),v.value("pubVersion")));var it=v.edges(Direction.OUT,"pubRelation");while(it.hasNext()){var e=it.next();String target=e.inVertex().value("pubId"),role=e.value("pubRole");if(!selected.containsKey(target))continue;if(edges.size()==300){truncated=true;break;}edges.add(new Edge(entry.getKey()+":"+role+":"+target,UUID.fromString(entry.getKey()),UUID.fromString(target),role));}}
  nodes.sort(Comparator.comparing(n->n.id().toString()));edges.sort(Comparator.comparing(Edge::id));return new View(List.copyOf(nodes),List.copyOf(edges),truncated);
 }finally{if(tx.isOpen())tx.rollback();}}
 void ping(){var tx=graph.newTransaction();try{tx.query().has("pubId","0199e000-0000-7000-8000-ffffffffffff").limit(1).vertices().iterator().hasNext();}finally{if(tx.isOpen())tx.rollback();}}
 void reset(){for(String kind:java.util.stream.Stream.concat(PublicProjection.KINDS.stream(),java.util.stream.Stream.of("UNKNOWN")).toList())while(true){var tx=graph.newTransaction();try{var rows=new ArrayList<Vertex>();tx.query().has("pubKind",kind).limit(100).vertices().forEach(rows::add);if(rows.isEmpty()){tx.rollback();break;}for(var row:rows)row.remove();tx.commit();}catch(RuntimeException e){if(tx.isOpen())tx.rollback();throw e;}}}
 public void close(){if(graph.isOpen())graph.close();}
}
