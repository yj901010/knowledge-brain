package io.knowledgebrain.brainquery;
import java.util.*;
import org.janusgraph.core.*;
import org.janusgraph.core.schema.*;
import org.apache.tinkerpop.gremlin.structure.*;

/** Separate typed network schema in the existing derived Cassandra keyspace. */
final class BrainGraphStore implements AutoCloseable {
 private final JanusGraph graph;
 BrainGraphStore(JanusGraph graph){this.graph=graph;}
 static void initialize(JanusGraph graph){var m=graph.openManagement();try{
  if(m.getVertexLabel("brainEntity")==null)m.makeVertexLabel("brainEntity").make();
  for(String k:List.of("brainKey","brainKind","brainId","brainSpace","brainTitle","brainHash","brainSubject","brainRelationId","brainRole"))if(m.getPropertyKey(k)==null)m.makePropertyKey(k).dataType(String.class).cardinality(Cardinality.SINGLE).make();
  if(m.getPropertyKey("brainVisible")==null)m.makePropertyKey("brainVisible").dataType(Boolean.class).cardinality(Cardinality.SINGLE).make();
  for(String k:List.of("brainSequence","brainCanonicalVersion"))if(m.getPropertyKey(k)==null){var p=m.makePropertyKey(k).dataType(Long.class).cardinality(Cardinality.SINGLE).make();m.setConsistency(p,ConsistencyModifier.LOCK);}
  if(m.getEdgeLabel("brainRelation")==null)m.makeEdgeLabel("brainRelation").multiplicity(Multiplicity.MULTI).make();
  if(m.getGraphIndex("brainEntityByKey")==null){var i=m.buildIndex("brainEntityByKey",Vertex.class).addKey(m.getPropertyKey("brainKey")).unique().buildCompositeIndex();m.setConsistency(i,ConsistencyModifier.LOCK);}
  if(m.getGraphIndex("brainEntitiesBySpaceVisible")==null)m.buildIndex("brainEntitiesBySpaceVisible",Vertex.class).addKey(m.getPropertyKey("brainSpace")).addKey(m.getPropertyKey("brainVisible")).buildCompositeIndex();
  if(m.getGraphIndex("brainEntitiesByKind")==null)m.buildIndex("brainEntitiesByKind",Vertex.class).addKey(m.getPropertyKey("brainKind")).buildCompositeIndex();
  m.commit();
 }catch(RuntimeException e){if(m.isOpen())m.rollback();throw e;}}
 private Vertex vertex(JanusGraphTransaction tx,String kind,UUID id,UUID space){String key=kind+":"+id;var it=tx.query().has("brainKey",key).vertices().iterator();if(it.hasNext()){var v=it.next();if(it.hasNext()||!v.value("brainSpace").equals(space.toString()))throw new BrainProjectionStore.Conflict();return v;}return tx.addVertex(T.label,"brainEntity","brainKey",key,"brainKind",kind,"brainId",id.toString(),"brainSpace",space.toString(),"brainTitle","","brainVisible",false,"brainSequence",0L,"brainCanonicalVersion",0L,"brainHash","","brainSubject","");}
 void apply(BrainProjectionStore.Document d){applyBatch(List.of(d));}
 void applyBatch(List<BrainProjectionStore.Document> documents){if(documents.isEmpty()||documents.size()>50)throw BrainProjection.invalid();var tx=graph.newTransaction();try{for(var d:documents){var v=vertex(tx,d.kind(),d.id(),d.spaceId());long version=v.value("brainSequence");var fields=new ArrayList<String>(List.of(d.key(),d.spaceId().toString(),""+d.canonicalVersion(),""+d.visible(),d.title(),d.subject()==null?"":d.subject().toString()));for(var r:d.refs())fields.addAll(List.of(r.id().toString(),r.role(),r.targetKind(),r.targetId().toString()));String hash=BrainProjection.hash(fields);if(version>d.sequence())continue;if(version==d.sequence()){if(!v.value("brainHash").equals(hash))throw new BrainProjectionStore.Conflict();continue;}
  var edges=v.edges(Direction.OUT,"brainRelation");while(edges.hasNext())edges.next().remove();v.property("brainSequence",d.sequence());v.property("brainCanonicalVersion",d.canonicalVersion());v.property("brainHash",hash);v.property("brainVisible",d.visible());v.property("brainTitle",d.title());v.property("brainSubject",d.subject()==null?"":d.subject().toString());
  for(var r:d.refs())v.addEdge("brainRelation",vertex(tx,r.targetKind(),r.targetId(),d.spaceId()),"brainRelationId",r.id().toString(),"brainRole",r.role());}tx.commit();
 }catch(RuntimeException e){if(tx.isOpen())tx.rollback();throw e;}}
 record Node(String key,String kind,UUID id,String title,long version,UUID subjectConceptId){}
 record Edge(String key,UUID relationId,String role,String source,String target){}
 record View(List<Node> nodes,List<Edge> edges,boolean truncated){}
 View read(UUID space,int limit){if(space==null||limit<1||limit>200)throw BrainProjection.invalid();var tx=graph.newTransaction();try{var selected=new LinkedHashMap<String,Vertex>();var nodes=new ArrayList<Node>();boolean truncated=false;for(var v:tx.query().has("brainSpace",space.toString()).has("brainVisible",true).limit(limit+1).vertices()){if(nodes.size()==limit){truncated=true;break;}String key=v.value("brainKey"),subject=v.value("brainSubject");selected.put(key,v);nodes.add(new Node(key,v.value("brainKind"),UUID.fromString(v.value("brainId")),v.value("brainTitle"),v.value("brainCanonicalVersion"),subject.isEmpty()?null:UUID.fromString(subject)));}var edges=new ArrayList<Edge>();outer:for(var row:selected.entrySet()){var it=row.getValue().edges(Direction.OUT,"brainRelation");while(it.hasNext()){var e=it.next();var target=e.inVertex();if(!target.value("brainSpace").equals(space.toString()))throw new BrainProjectionStore.Conflict();String key=target.value("brainKey");if(!selected.containsKey(key))continue;if(edges.size()==1000){truncated=true;break outer;}UUID id=UUID.fromString(e.value("brainRelationId"));String role=e.value("brainRole");edges.add(new Edge(row.getKey()+":"+id+":"+role,id,role,row.getKey(),key));}}nodes.sort(Comparator.comparing(Node::key));edges.sort(Comparator.comparing(Edge::key));return new View(List.copyOf(nodes),List.copyOf(edges),truncated);}finally{if(tx.isOpen())tx.rollback();}}
 void ping(){var tx=graph.newTransaction();try{tx.query().has("brainKey","NOTE:019a0000-0000-7000-8000-ffffffffffff").limit(1).vertices().iterator().hasNext();}finally{if(tx.isOpen())tx.rollback();}}
 void reset(){for(String kind:List.of("NOTE","CONCEPT","CLAIM","BOOKMARK","QUESTION","DECISION"))for(int batch=0;batch<100000;batch++){var tx=graph.newTransaction();try{var rows=new ArrayList<Vertex>();tx.query().has("brainKind",kind).limit(200).vertices().forEach(rows::add);if(rows.isEmpty()){tx.rollback();break;}for(var v:rows)v.remove();tx.commit();}catch(RuntimeException e){if(tx.isOpen())tx.rollback();throw e;}}}
 @Override public void close(){if(graph.isOpen())graph.close();}
}
