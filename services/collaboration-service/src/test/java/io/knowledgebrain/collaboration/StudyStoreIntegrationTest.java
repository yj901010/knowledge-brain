package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.collaboration.study.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_COLLABORATION_DATABASE_URL",matches=".+")
class StudyStoreIntegrationTest {
 GroupStoreIntegrationTest f;StudyStore store;UUID group,space;
 @BeforeEach void setup(){f=new GroupStoreIntegrationTest();f.setup();space=f.ids.next();var g=f.store.request(f.owner,f.key(),"Study Group","",f.trace);group=g.groupId();f.store.activate(group,f.owner,space,f.trace);store=new StudyStore(f.source,Clock.systemUTC());}
 StudyStore.Result create(String key){return store.create(group,space,f.owner,key,"Study <script>","line\n공유 목적",f.trace);}
 void status(int code,org.junit.jupiter.api.function.Executable op){assertEquals(code,assertThrows(ResponseStatusException.class,op).getStatusCode().value());}
 int events(UUID id){return f.jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Integer.class,id);}
 @Test void completeCanonicalSnapshotAuditAndProtobufCommitTogether()throws Exception{
  var v=create(f.key()).study();assertEquals(7,v.studyId().version());assertEquals("PLANNED",v.state());assertEquals("PRIVATE",v.visibility());assertEquals(v,store.get(group,space,v.studyId()));
  var rev=store.history(group,space,v.studyId(),Long.MAX_VALUE,10);assertEquals(1,rev.items().size());assertNull(rev.nextBefore());assertEquals(v,rev.items().getFirst().snapshot());assertEquals(f.owner,rev.items().getFirst().actorId());
  var e=StudyEnvelopeV1.parseFrom(f.jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,v.studyId()));var p=StudySnapshotV1.parseFrom(e.getPayload());
  assertEquals("StudyCreated",e.getEventType());assertEquals(space.toString(),e.getSpaceId());assertEquals(f.owner.toString(),e.getActorId());assertEquals(f.trace,e.getTraceId());assertEquals(group.toString(),p.getGroupId());assertEquals(v.title(),p.getTitle());assertEquals(v.description(),p.getDescription());assertEquals(v.createdAt(),p.getCreatedAt());assertEquals("PLANNED",p.getState());assertEquals(1,p.getVersion());
 }
 @Test void creationRetrySurvivesRestartEditsAndRetirementWithoutRestoring(){
  String key=f.key();var v=create(key).study();assertFalse(create(key).created());status(409,()->store.create(group,space,f.owner,key,"Different","",f.trace));
  var edited=store.metadata(group,space,v.studyId(),f.owner,1,"Edited","Purpose",f.trace);assertEquals(edited,new StudyStore(f.source,Clock.systemUTC()).create(group,space,f.owner,key,v.title(),v.description(),f.trace).study());
  var retired=store.state(group,space,v.studyId(),f.owner,2,"RETIRED",f.trace);assertEquals(retired,create(key).study());assertEquals(3,events(v.studyId()));assertEquals("RETIRED",store.get(group,space,v.studyId()).state());
 }
 @Test void strictLifecycleNoOpsAndVersionsProtectAgainstLostUpdates(){
  var v=create(f.key()).study();status(409,()->store.state(group,space,v.studyId(),f.owner,1,"COMPLETED",f.trace));status(412,()->store.metadata(group,space,v.studyId(),f.owner,2,"Wrong","",f.trace));
  assertEquals(v,store.metadata(group,space,v.studyId(),f.owner,1,v.title(),v.description(),f.trace));assertEquals(1,events(v.studyId()));
  var active=store.state(group,space,v.studyId(),f.owner,1,"ACTIVE",f.trace);assertEquals(active,store.state(group,space,v.studyId(),f.owner,2,"ACTIVE",f.trace));status(409,()->store.state(group,space,v.studyId(),f.owner,2,"PLANNED",f.trace));
  store.state(group,space,v.studyId(),f.owner,2,"COMPLETED",f.trace);store.metadata(group,space,v.studyId(),f.owner,3,"Corrected","",f.trace);var retired=store.state(group,space,v.studyId(),f.owner,4,"RETIRED",f.trace);
  assertEquals(retired,store.state(group,space,v.studyId(),f.owner,5,"RETIRED",f.trace));status(409,()->store.metadata(group,space,v.studyId(),f.owner,5,"Restore","",f.trace));status(409,()->store.state(group,space,v.studyId(),f.owner,5,"ACTIVE",f.trace));assertEquals(5,events(v.studyId()));
 }
 @Test void concurrentCreateAndEditEachHaveOneCanonicalWinner()throws Exception{
  String key=f.key();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var barrier=new java.util.concurrent.CyclicBarrier(2);
   var a=pool.submit(()->{barrier.await();return create(key);});var b=pool.submit(()->{barrier.await();return create(key);});var x=a.get();var y=b.get();assertNotEquals(x.created(),y.created());assertEquals(x.study(),y.study());assertEquals(1,events(x.study().studyId()));
   var results=new ArrayList<java.util.concurrent.Future<Boolean>>();for(String title:List.of("First","Second"))results.add(pool.submit(()->{barrier.await();try{store.metadata(group,space,x.study().studyId(),f.owner,1,title,"",f.trace);return true;}catch(ResponseStatusException e){assertEquals(412,e.getStatusCode().value());return false;}}));assertNotEquals(results.get(0).get(),results.get(1).get());assertEquals(2,events(x.study().studyId()));
  }
 }
 @Test void outboxFailureRollsBackCreationAndExistingRevisions(){
  String constraint="study_fail_"+group.toString().replace("-","");String check=" CHECK (aggregatetype <> 'study' OR trace_id <> '"+f.trace+"') NOT VALID";
  f.jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+check);
  try{assertThrows(RuntimeException.class,()->create(f.key()));assertTrue(store.list(group,space,null,20).items().isEmpty());}finally{f.jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
  var v=create(f.key()).study();f.jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+check);
  try{assertThrows(RuntimeException.class,()->store.state(group,space,v.studyId(),f.owner,1,"ACTIVE",f.trace));assertEquals(v,store.get(group,space,v.studyId()));assertEquals(1,store.history(group,space,v.studyId(),Long.MAX_VALUE,10).items().size());assertEquals(1,events(v.studyId()));}finally{f.jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
 }
 @Test void scopedCatalogAndHistoryPaginationNeverCrossGroupOrSpace(){
  var a=create(f.key()).study();create(f.key());create(f.key());var page=store.list(group,space,null,2);assertEquals(2,page.items().size());assertNotNull(page.nextAfter());assertEquals(1,store.list(group,space,page.nextAfter(),2).items().size());
  status(404,()->store.get(group,f.owner,a.studyId()));status(404,()->store.get(f.owner,space,a.studyId()));status(404,()->store.create(group,f.owner,f.owner,f.key(),"Wrong Space","",f.trace));
  var pending=f.store.request(f.owner,f.key(),"Pending","",f.trace);status(404,()->store.create(pending.groupId(),space,f.owner,f.key(),"Pending","",f.trace));
  store.metadata(group,space,a.studyId(),f.owner,1,"v2","",f.trace);store.metadata(group,space,a.studyId(),f.owner,2,"v3","",f.trace);var h=store.history(group,space,a.studyId(),Long.MAX_VALUE,2);assertEquals(List.of(3L,2L),h.items().stream().map(StudyStore.Revision::version).toList());assertEquals(2L,h.nextBefore());assertEquals(1,store.history(group,space,a.studyId(),h.nextBefore(),2).items().size());
 }
}
