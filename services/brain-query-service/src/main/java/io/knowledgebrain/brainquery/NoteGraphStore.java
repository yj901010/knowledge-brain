package io.knowledgebrain.brainquery;

import java.util.*;
import org.apache.tinkerpop.gremlin.structure.*;
import org.janusgraph.core.*;
import org.janusgraph.core.schema.*;

/** Cassandra-backed JanusGraph adapter. Only an already-authorized Space may be read.
 * Schema creation belongs to a separate operator, never the runtime projection role.
 * Kafka/inbox orchestration and public authorization transport are deliberately separate.
 */
public final class NoteGraphStore implements AutoCloseable {
    private final JanusGraph graph;
    public NoteGraphStore(JanusGraph graph) { this.graph=Objects.requireNonNull(graph); }
    public static JanusGraph open(String host,String username,String password,String truststore,String trustPassword) {
        return JanusGraphFactory.build().set("storage.backend","cql").set("storage.hostname",host)
                .set("storage.cql.keyspace","brain_graph").set("storage.cql.local-datacenter","datacenter1")
                .set("storage.username",username).set("storage.password",password)
                .set("storage.cql.ssl.enabled",true).set("storage.cql.ssl.hostname_validation",true)
                .set("storage.cql.ssl.truststore.location",truststore).set("storage.cql.ssl.truststore.password",trustPassword)
                .set("storage.cql.replication-factor",1).set("storage.cql.request-timeout",10000L)
                .set("storage.cql.netty.io-size",2).set("storage.cql.netty.admin-size",1)
                .set("storage.cql.executor-service.core-pool-size",2).set("storage.cql.executor-service.max-pool-size",4)
                .set("storage.cql.atomic-batch-mutate",true).set("storage.cql.read-consistency-level","QUORUM").set("storage.cql.write-consistency-level","QUORUM")
                .set("cache.db-cache",false).set("schema.default","none").set("query.force-index",true).open();
    }
    public static void initializeSchema(JanusGraph graph) {
        var m=graph.openManagement();
        try {
            if(m.getVertexLabel("note")==null) m.makeVertexLabel("note").make();
            for(String key:List.of("noteId","spaceId","title","noteState","linkState","noteHash","linkHash","linkId"))
                if(m.getPropertyKey(key)==null) m.makePropertyKey(key).dataType(String.class).cardinality(Cardinality.SINGLE).make();
            if(m.getPropertyKey("visible")==null) m.makePropertyKey("visible").dataType(Boolean.class).cardinality(Cardinality.SINGLE).make();
            for(String key:List.of("noteVersion","linkVersion")) {
                if(m.getPropertyKey(key)==null) {
                    var property=m.makePropertyKey(key).dataType(Long.class).cardinality(Cardinality.SINGLE).make();
                    m.setConsistency(property,ConsistencyModifier.LOCK);
                }
            }
            if(m.getPropertyKey("ordinal")==null) m.makePropertyKey("ordinal").dataType(Integer.class).make();
            if(m.getEdgeLabel("wiki")==null) m.makeEdgeLabel("wiki").multiplicity(Multiplicity.MULTI).make();
            if(m.getGraphIndex("noteById")==null) {
                var index=m.buildIndex("noteById",Vertex.class).addKey(m.getPropertyKey("noteId")).unique().buildCompositeIndex();
                m.setConsistency(index,ConsistencyModifier.LOCK);
            }
            if(m.getGraphIndex("notesBySpaceVisible")==null)
                m.buildIndex("notesBySpaceVisible",Vertex.class).addKey(m.getPropertyKey("spaceId")).addKey(m.getPropertyKey("visible")).buildCompositeIndex();
            m.commit();
        } catch(RuntimeException e) { if(m.isOpen())m.rollback(); throw e; }
    }
    private static Vertex vertex(JanusGraphTransaction tx,UUID noteId,UUID spaceId) {
        var vertices=tx.query().has("noteId",noteId.toString()).vertices().iterator();
        Vertex v;
        if(vertices.hasNext()) {
            v=vertices.next();
            if(vertices.hasNext() || !v.value("spaceId").equals(spaceId.toString())) throw new IllegalStateException("Graph Space conflict");
        } else {
            v=tx.addVertex(T.label,"note","noteId",noteId.toString(),"spaceId",spaceId.toString(),"noteState","UNKNOWN","linkState","UNKNOWN","title","","noteVersion",0L,"linkVersion",0L,"noteHash","","linkHash","","visible",false);
        }
        return v;
    }
    public void apply(NoteSearchProjection.Revision note) {
        var tx=graph.newTransaction();
        try {
            var v=vertex(tx,note.noteId(),note.spaceId());long version=v.value("noteVersion");
            String hash=note.state()+"\n"+note.title();
            if(note.version()<version) { tx.rollback(); return; }
            if(note.version()==version) {
                if(!v.value("noteHash").equals(hash)) throw new IllegalStateException("Graph note version conflict");
                tx.rollback();return;
            }
            if(v.value("noteState").equals("RETIRED") && !note.state().equals("RETIRED")) throw new IllegalStateException("Graph tombstone conflict");
            v.property("noteVersion",note.version());v.property("noteHash",hash);
            v.property("noteState",note.state());v.property("title",note.state().equals("RETIRED")?"":note.title());
            v.property("visible",note.state().equals("ACTIVE") && !v.value("linkState").equals("RETIRED"));
            if(note.state().equals("RETIRED")) removeOutgoing(v);
            tx.commit();
        } catch(RuntimeException e) { if(tx.isOpen())tx.rollback();throw e; }
    }
    public void apply(NoteGraphProjection.Links links) {
        var tx=graph.newTransaction();
        try {
            var v=vertex(tx,links.sourceNoteId(),links.spaceId());long version=v.value("linkVersion");
            if(links.linkVersion()<version) { tx.rollback();return; }
            if(links.linkVersion()==version) {
                if(!v.value("linkHash").equals(links.payloadHash())) throw new IllegalStateException("Graph link version conflict");
                tx.rollback();return;
            }
            if(v.value("linkState").equals("RETIRED") && !links.state().equals("RETIRED")) throw new IllegalStateException("Graph link tombstone conflict");
            removeOutgoing(v);v.property("linkVersion",links.linkVersion());v.property("linkHash",links.payloadHash());v.property("linkState",links.state());
            v.property("visible",links.state().equals("ACTIVE") && v.value("noteState").equals("ACTIVE"));
            if(links.state().equals("ACTIVE") && !v.value("noteState").equals("RETIRED"))
                for(var link:links.resolved()) v.addEdge("wiki",vertex(tx,link.targetNoteId(),links.spaceId()),"linkId",link.linkId().toString(),"ordinal",link.ordinal());
            tx.commit();
        } catch(RuntimeException e) { if(tx.isOpen())tx.rollback();throw e; }
    }
    private static void removeOutgoing(Vertex vertex) { var edges=vertex.edges(Direction.OUT,"wiki");while(edges.hasNext())edges.next().remove(); }
    public record Node(UUID noteId,String title) {}
    public record Link(UUID linkId,UUID sourceNoteId,UUID targetNoteId) {}
    public record View(List<Node> nodes,List<Link> links,boolean truncated) {}
    public View read(UUID authorizedSpace,int limit) {
        if(authorizedSpace==null || limit<1 || limit>200)throw new IllegalArgumentException("Invalid graph query");
        var tx=graph.newTransaction();
        try {
            var nodes=new ArrayList<Node>();var selected=new LinkedHashMap<String,Vertex>();boolean truncated=false;
            for(var v:tx.query().has("spaceId",authorizedSpace.toString()).has("visible",true).limit(limit+1).vertices()) {
                if(nodes.size()==limit){truncated=true;break;}
                String id=v.value("noteId");selected.put(id,v);nodes.add(new Node(UUID.fromString(id),v.value("title")));
            }
            var links=new ArrayList<Link>();
            outer:for(var entry:selected.entrySet()) {
                var outgoing=entry.getValue().edges(Direction.OUT,"wiki");
                while(outgoing.hasNext()) {
                    var edge=outgoing.next();var target=edge.inVertex();String targetId=target.value("noteId");
                    if(!target.value("spaceId").equals(authorizedSpace.toString()))throw new IllegalStateException("Graph Space conflict");
                    if(!selected.containsKey(targetId))continue;
                    if(links.size()==1000){truncated=true;break outer;}
                    links.add(new Link(UUID.fromString(edge.value("linkId")),UUID.fromString(entry.getKey()),UUID.fromString(targetId)));
                }
            }
            nodes.sort(Comparator.comparing(n->n.noteId().toString()));links.sort(Comparator.comparing(l->l.linkId().toString()));
            return new View(List.copyOf(nodes),List.copyOf(links),truncated);
        } finally { if(tx.isOpen())tx.rollback(); }
    }
    void initialize(){initializeSchema(graph);}
    void ping(){var tx=graph.newTransaction();try{tx.query().has("noteId","019a0000-0000-7000-8000-ffffffffffff").limit(1).vertices().iterator().hasNext();}finally{if(tx.isOpen())tx.rollback();}}
    @Override public void close() { if(graph.isOpen())graph.close(); }
}
