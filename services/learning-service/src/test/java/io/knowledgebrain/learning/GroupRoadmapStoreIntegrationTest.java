package io.knowledgebrain.learning;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.learning.grouproadmap.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_DATABASE_URL",matches=".+")
class GroupRoadmapStoreIntegrationTest {
 RoadmapAdoptionStoreIntegrationTest f;GroupRoadmapStore store;
 @BeforeEach void setup(){f=new RoadmapAdoptionStoreIntegrationTest();f.setup();store=new GroupRoadmapStore(f.ds,f.published,Clock.systemUTC());}
 @Test void completePinnedGraphIsIndependentOfPublicChangesAndPrivateData()throws Exception{
  var source=f.publish();var secret=f.personal.create(f.actor,f.actor,"Secret private roadmap","Not shared",f.trace);
  var v=store.attach(f.space,f.sourceSpace,source.roadmapId(),1,f.actor,f.trace).link();
  assertEquals(source,v.source());assertEquals("PRIVATE",v.visibility());assertEquals(7,v.linkId().version());
  f.update(source);assertEquals(source,store.get(f.space,v.linkId()).source());assertEquals(v,store.attach(f.space,f.sourceSpace,source.roadmapId(),1,f.actor,f.trace).link());
  f.status(409,()->store.attach(f.reviewer,f.sourceSpace,source.roadmapId(),1,f.actor,f.trace));
  f.status(404,()->store.attach(f.space,f.actor,secret.roadmapId(),1,f.actor,f.trace));f.status(404,()->store.get(f.actor,v.linkId()));
  assertTrue(f.personal.list(f.space,null,20).items().isEmpty());assertEquals(secret,f.personal.get(f.actor,secret.roadmapId()));
  var env=GroupRoadmapEnvelopeV1.parseFrom(f.sql.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,v.linkId()));var p=GroupRoadmapChangedV1.parseFrom(env.getPayload());
  assertEquals("GroupRoadmapLinked",env.getEventType());assertEquals(f.space.toString(),env.getSpaceId());assertEquals(f.actor.toString(),env.getActorId());assertEquals(f.trace,env.getTraceId());assertEquals(2,p.getNodesCount());assertEquals(1,p.getEdgesCount());assertEquals(1,p.getSourceVersion());assertEquals("PRIVATE",p.getVisibility());
 }
 @Test void retirementHasAuditAndOptimisticConcurrencyAndNeverRestoresOnRetry(){
  var s=f.publish();var v=store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace).link();
  f.status(412,()->store.retire(f.space,v.linkId(),2,f.actor,f.trace));
  var retired=store.retire(f.space,v.linkId(),1,f.reviewer,f.trace);assertEquals("RETIRED",retired.state());assertEquals(2,retired.version());
  f.status(412,()->store.retire(f.space,v.linkId(),1,f.actor,f.trace));assertEquals(retired,store.retire(f.space,v.linkId(),2,f.actor,f.trace));
  assertEquals(retired,store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace).link());assertEquals(2,f.events(v.linkId()));
  var history=store.history(f.space,v.linkId(),Long.MAX_VALUE,1);assertEquals(f.reviewer,history.getFirst().actorId());assertEquals(retired,history.getFirst().snapshot());
  assertEquals(v,store.history(f.space,v.linkId(),2,1).getFirst().snapshot());
 }
 @Test void simultaneousAttachAndRetireEachHaveOneWinner()throws Exception{
  var s=f.publish();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
   var gate=new java.util.concurrent.CyclicBarrier(2);var a=pool.submit(()->{gate.await();return store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace);});var b=pool.submit(()->{gate.await();return store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace);});
   var x=a.get();var y=b.get();assertNotEquals(x.created(),y.created());assertEquals(x.link(),y.link());assertEquals(1,f.events(x.link().linkId()));
   var calls=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int n=0;n<2;n++)calls.add(pool.submit(()->{gate.await();try{store.retire(f.space,x.link().linkId(),1,f.actor,f.trace);return true;}catch(org.springframework.web.server.ResponseStatusException e){assertEquals(412,e.getStatusCode().value());return false;}}));assertNotEquals(calls.get(0).get(),calls.get(1).get());assertEquals(2,f.events(x.link().linkId()));
  }
 }
 @Test void outboxFailureRollsBackLinkAndRevisionAndRetirement(){
  var s=f.publish();String constraint="group_link_fail_"+f.space.toString().replace("-","");
  String check=" CHECK (aggregatetype <> 'group-roadmap' OR trace_id <> '"+f.trace+"') NOT VALID";
  f.sql.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+check);
  try{assertThrows(RuntimeException.class,()->store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace));assertTrue(store.list(f.space,null,20).items().isEmpty());}finally{f.sql.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
  var v=store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace).link();f.sql.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+check);
  try{assertThrows(RuntimeException.class,()->store.retire(f.space,v.linkId(),1,f.actor,f.trace));assertEquals(v,store.get(f.space,v.linkId()));assertEquals(1,store.history(f.space,v.linkId(),Long.MAX_VALUE,10).size());}finally{f.sql.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
 }
 @Test void catalogPaginationAndOtherSpacesAreBounded(){
  for(int n=0;n<3;n++){var s=f.publish();store.attach(f.space,f.sourceSpace,s.roadmapId(),1,f.actor,f.trace);}
  var a=store.list(f.space,null,2);assertEquals(2,a.items().size());assertNotNull(a.nextAfter());var b=store.list(f.space,a.nextAfter(),2);assertEquals(1,b.items().size());assertNull(b.nextAfter());assertTrue(store.list(f.actor,null,2).items().isEmpty());
 }
}
