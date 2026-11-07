package io.knowledgebrain.collaboration;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;import org.springframework.web.server.ResponseStatusException;
import io.knowledgebrain.contracts.collaboration.studyroadmap.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_COLLABORATION_DATABASE_URL",matches=".+")
class StudyRoadmapStoreIntegrationTest {
 StudyStoreIntegrationTest f;StudyRoadmapStore store;UUID study;LearningRoadmapClient.Reference source;
 @BeforeEach void setup(){f=new StudyStoreIntegrationTest();f.setup();study=f.create(f.f.key()).study().studyId();store=new StudyRoadmapStore(f.f.source,f.store,Clock.systemUTC());source=reference();}
 LearningRoadmapClient.Reference reference(){return new LearningRoadmapClient.Reference(f.f.owner,f.space,f.f.ids.next(),1,"ACTIVE",f.f.ids.next(),f.f.ids.next(),1,"Pinned roadmap");}
 StudyRoadmapStore.Current link(long version,LearningRoadmapClient.Reference ref){return store.change(f.group,f.space,study,f.f.owner,version,ref,f.f.trace);}
 @Test void referenceOnlyBindingAuditAndOutboxAreAtomic()throws Exception{
  assertNull(store.get(f.group,f.space,study).binding());var b=link(0,source).binding();assertEquals(7,b.bindingId().version());assertEquals("LINKED",b.state());assertEquals(source.roadmapLinkId(),b.roadmapLinkId());assertEquals("PRIVATE",b.visibility());assertEquals(b,store.get(f.group,f.space,study).binding());assertEquals(b,store.history(f.group,f.space,study,Long.MAX_VALUE,5).items().getFirst().snapshot());
  var e=StudyRoadmapEnvelopeV1.parseFrom(f.f.jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,b.bindingId()));var p=StudyRoadmapSnapshotV1.parseFrom(e.getPayload());assertEquals("StudyRoadmapLinked",e.getEventType());assertEquals(f.space.toString(),e.getSpaceId());assertEquals(f.f.owner.toString(),e.getActorId());assertEquals(f.f.trace,e.getTraceId());assertEquals(source.roadmapLinkId().toString(),p.getRoadmapLinkId());assertEquals(study.toString(),p.getStudyId());assertEquals(1,p.getVersion());assertEquals(1,f.store.get(f.group,f.space,study).version());
 }
 @Test void replacementUnlinkAndExplicitRelinkPreserveOneAggregateAndVersionHistory(){
  var a=link(0,source).binding();assertEquals(a,link(1,source).binding());f.status(412,()->link(0,source));var next=reference();var b=link(1,next).binding();assertEquals(a.bindingId(),b.bindingId());assertEquals(2,b.version());assertEquals(next.roadmapLinkId(),b.roadmapLinkId());var retired=link(2,null).binding();assertEquals("UNLINKED",retired.state());assertEquals(retired,link(3,null).binding());var restored=link(3,source).binding();assertEquals(a.bindingId(),restored.bindingId());assertEquals(4,restored.version());assertEquals(4,f.events(a.bindingId()));
  var h=store.history(f.group,f.space,study,Long.MAX_VALUE,2);assertEquals(List.of(4L,3L),h.items().stream().map(StudyRoadmapStore.Revision::version).toList());assertEquals(3L,h.nextBefore());assertEquals(List.of(2L,1L),store.history(f.group,f.space,study,h.nextBefore(),2).items().stream().map(StudyRoadmapStore.Revision::version).toList());
 }
 @Test void archivedStudyCannotBindOrUnlinkAndScopeNeverCrosses(){
  assertNull(link(0,null).binding());var a=link(0,source).binding();f.status(404,()->store.get(f.group,f.f.owner,study));f.status(404,()->store.get(f.f.owner,f.space,study));f.store.state(f.group,f.space,study,f.f.owner,1,"RETIRED",f.f.trace);f.status(409,()->link(1,reference()));f.status(409,()->link(1,null));assertEquals(a,store.get(f.group,f.space,study).binding());
 }
 @Test void concurrentBindingAndStudyRetirementSerialize()throws Exception{
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var gate=new java.util.concurrent.CyclicBarrier(2);var calls=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int n=0;n<2;n++)calls.add(pool.submit(()->{gate.await();try{link(0,reference());return true;}catch(ResponseStatusException e){assertEquals(412,e.getStatusCode().value());return false;}}));assertNotEquals(calls.get(0).get(),calls.get(1).get());assertEquals(1,f.events(store.get(f.group,f.space,study).binding().bindingId()));}
 }
 @Test void retirementRacingSourceValidationNeverMutatesAnArchivedStudy()throws Exception{
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var gate=new java.util.concurrent.CyclicBarrier(2);var bind=pool.submit(()->{gate.await();try{return link(0,source).binding();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return null;}});var retire=pool.submit(()->{gate.await();return f.store.state(f.group,f.space,study,f.f.owner,1,"RETIRED",f.f.trace);});var result=bind.get();assertEquals("RETIRED",retire.get().state());assertEquals(result,store.get(f.group,f.space,study).binding());}
 }
 @Test void outboxFailureRollsBackBothFirstBindingAndExistingRelationship(){
  String constraint="study_roadmap_fail_"+study.toString().replace("-","");String check=" CHECK(aggregatetype <> 'study-roadmap' OR trace_id <> '"+f.f.trace+"') NOT VALID";
  f.f.jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+check);try{assertThrows(RuntimeException.class,()->link(0,source));assertNull(store.get(f.group,f.space,study).binding());}finally{f.f.jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
  var a=link(0,source).binding();f.f.jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+check);try{assertThrows(RuntimeException.class,()->link(1,null));assertEquals(a,store.get(f.group,f.space,study).binding());assertEquals(1,store.history(f.group,f.space,study,Long.MAX_VALUE,5).items().size());assertEquals(1,f.events(a.bindingId()));}finally{f.f.jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
 }
}
