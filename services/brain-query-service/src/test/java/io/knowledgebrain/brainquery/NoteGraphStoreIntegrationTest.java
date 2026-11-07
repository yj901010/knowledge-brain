package io.knowledgebrain.brainquery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_GRAPH_HOST",matches=".+")
class NoteGraphStoreIntegrationTest {
    private static String secret(String role,String key)throws Exception{return Files.readString(Path.of("/graph-"+role,key)).strip();}
    private static UUID id(){return UUID.fromString("019a0000-0000-7000-8000-"+String.format("%012x",new java.security.SecureRandom().nextLong(1L<<48)));}
    private static NoteSearchProjection.Revision note(UUID space,UUID id,long version,String state,String title){return new NoteSearchProjection.Revision(id(),space,id,version,state,state.equals("RETIRED")?"":title,"");}
    private static NoteGraphProjection.Links links(UUID space,UUID source,long version,String state,NoteGraphProjection.Link... edges){return new NoteGraphProjection.Links(id(),space,source,1,version,state,List.of(edges),state+version+Arrays.toString(edges));}
    @Test void realCassandraProjectionSurvivesReopenAndIndependentOrdering()throws Exception {
        String host=System.getenv("BRAIN_TEST_GRAPH_HOST"),trust="/graph-trust/truststore.p12",trustPassword=secret("projector","password");
        UUID proofSpace=UUID.fromString("019a0000-0000-7000-8000-0000000a0001"),proofNote=UUID.fromString("019a0000-0000-7000-8000-0000000a0002");
        if("true".equals(System.getenv("BRAIN_TEST_GRAPH_RESTART_VERIFY"))) {
            try(var store=new NoteGraphStore(NoteGraphStore.open(host,secret("projector","username"),secret("projector","password"),trust,trustPassword))) {
                assertTrue(store.read(proofSpace,10).nodes().stream().anyMatch(n->n.noteId().equals(proofNote) && n.title().equals("Graph restart proof")));
            }
            System.out.println("PASS actual JanusGraph vertices persisted after Cassandra Pod replacement");return;
        }
        var operator=NoteGraphStore.open(host,secret("admin","username"),secret("admin","password"),trust,trustPassword);
        NoteGraphStore.initializeSchema(operator);operator.close();
        var space=id();var source=id();var target=id();var otherSpace=id();var otherNote=id();var linkId=id();
        try(var store=new NoteGraphStore(NoteGraphStore.open(host,secret("projector","username"),secret("projector","password"),trust,trustPassword))) {
            store.apply(note(proofSpace,proofNote,2,"ACTIVE","Graph restart proof"));
            var initial=links(space,source,1,"ACTIVE",new NoteGraphProjection.Link(linkId,target,0));
            store.apply(initial);assertTrue(store.read(space,100).nodes().isEmpty());
            store.apply(note(space,source,1,"ACTIVE","Source"));assertEquals(1,store.read(space,100).nodes().size());assertTrue(store.read(space,100).links().isEmpty());
            store.apply(note(space,target,1,"ACTIVE","Target"));
            assertEquals(2,store.read(space,100).nodes().size());assertEquals(1,store.read(space,100).links().size());
            store.apply(initial);assertEquals(1,store.read(space,100).links().size());
            store.apply(note(otherSpace,otherNote,1,"ACTIVE","Private other Space"));assertEquals(1,store.read(otherSpace,100).nodes().size());
            assertThrows(IllegalStateException.class,()->store.apply(note(otherSpace,source,2,"ACTIVE","Moved")));
            assertThrows(IllegalStateException.class,()->store.apply(links(space,source,2,"ACTIVE",new NoteGraphProjection.Link(id(),otherNote,0))));
            assertEquals(1,store.read(space,100).links().size(),"cross-Space failure must roll back the complete snapshot");
            assertThrows(IllegalStateException.class,()->store.apply(links(space,source,1,"ACTIVE")));
            store.apply(links(space,source,2,"ACTIVE"));store.apply(initial);assertTrue(store.read(space,100).links().isEmpty());
            store.apply(links(space,source,3,"ACTIVE",new NoteGraphProjection.Link(linkId,target,0),new NoteGraphProjection.Link(id(),target,1)));
            assertEquals(2,store.read(space,100).links().size(),"duplicate occurrences preserve distinct link IDs");
            store.apply(note(space,target,2,"RETIRED",""));store.apply(note(space,target,1,"ACTIVE","Old target"));
            assertEquals(1,store.read(space,100).nodes().size());assertTrue(store.read(space,100).links().isEmpty());
            store.apply(links(space,source,4,"RETIRED"));assertTrue(store.read(space,100).nodes().isEmpty(),"retired link snapshot arriving before retired note must hide source");
            store.apply(note(space,source,2,"RETIRED",""));store.apply(note(space,source,1,"ACTIVE","Old source"));store.apply(initial);
            assertTrue(store.read(space,100).nodes().isEmpty());
            var concurrentSpace=id();var concurrentNote=id();store.apply(note(concurrentSpace,concurrentNote,1,"ACTIVE","Initial"));
            try(var second=new NoteGraphStore(NoteGraphStore.open(host,secret("projector","username"),secret("projector","password"),trust,trustPassword));var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var barrier=new java.util.concurrent.CyclicBarrier(2);
                var first=executor.submit(()->{barrier.await();try{store.apply(note(concurrentSpace,concurrentNote,2,"ACTIVE","Writer A"));return true;}catch(RuntimeException e){return false;}});
                var another=executor.submit(()->{barrier.await();try{second.apply(note(concurrentSpace,concurrentNote,2,"ACTIVE","Writer B"));return true;}catch(RuntimeException e){return false;}});
                assertNotEquals(first.get(30,java.util.concurrent.TimeUnit.SECONDS),another.get(30,java.util.concurrent.TimeUnit.SECONDS),"conflicting concurrent version may have only one successful writer");
            }
            assertThrows(IllegalArgumentException.class,()->store.read(space,201));
            assertThrows(IllegalArgumentException.class,()->store.read(null,1));
        }
        try(var reopened=new NoteGraphStore(NoteGraphStore.open(host,secret("projector","username"),secret("projector","password"),trust,trustPassword))) {
            assertTrue(reopened.read(space,100).nodes().isEmpty());assertEquals("Private other Space",reopened.read(otherSpace,100).nodes().getFirst().title());
        }
        System.out.println("PASS actual JanusGraph/Cassandra; scoped projection; independent versions; tombstones; reopened persistence");
    }
}
