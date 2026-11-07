package io.knowledgebrain.learning;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;import org.postgresql.ds.PGSimpleDataSource;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.learning.roadmapadoption.v1.*;
import io.knowledgebrain.contracts.learning.roadmap.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_DATABASE_URL",matches=".+")
class RoadmapAdoptionStoreIntegrationTest {
 RoadmapAdoptionStore store;RoadmapStore personal;PublicRoadmapStore published;JdbcTemplate sql;PGSimpleDataSource ds;
 UUID space,sourceSpace,author,actor,reviewer;String trace="b".repeat(32);
 @BeforeEach void setup(){
  ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_LEARNING_DATABASE_URL"));ds.setUser("brain_learning");ds.setPassword(System.getenv("BRAIN_TEST_LEARNING_DATABASE_PASSWORD"));
  Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();sql=new JdbcTemplate(ds);personal=new RoadmapStore(ds,Clock.systemUTC());published=new PublicRoadmapStore(ds,Clock.systemUTC());store=new RoadmapAdoptionStore(ds,published,personal,Clock.systemUTC());
  var ids=new UuidV7(Clock.systemUTC());space=ids.next();sourceSpace=ids.next();author=ids.next();actor=ids.next();reviewer=ids.next();
 }
 PublicRoadmapStore.Roadmap publish(){
  var p=published.submit(sourceSpace,author,null,0,"Adoption graph","Public description","Explicit",PublicRoadmapStoreIntegrationTest.graph(),trace);
  published.review(sourceSpace,p.contributionId(),reviewer,1,"APPROVED","Checked",trace);published.merge(sourceSpace,p.contributionId(),reviewer,2,trace);return published.get(sourceSpace,p.roadmapId());
 }
 void update(PublicRoadmapStore.Roadmap v){var p=published.submit(sourceSpace,author,v.roadmapId(),v.version(),"New public title","Updated","Explicit",PublicRoadmapStoreIntegrationTest.edit(v),trace);published.review(sourceSpace,p.contributionId(),reviewer,1,"APPROVED","Checked",trace);published.merge(sourceSpace,p.contributionId(),reviewer,2,trace);}
 int events(UUID id){return sql.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Integer.class,id);}
 void status(int code,org.junit.jupiter.api.function.Executable action){assertEquals(code,assertThrows(ResponseStatusException.class,action).getStatusCode().value());}
 @Test void completePrivateGraphProvenanceAndBothOutboxesCommitTogether()throws Exception{
  var original=publish();var result=store.adopt(space,sourceSpace,original.roadmapId(),1,actor,trace);assertTrue(result.created());var a=result.adoption();
  var copy=personal.get(space,a.roadmapId());assertEquals(1,copy.version());assertEquals("PRIVATE",copy.visibility());assertEquals(original.title(),copy.title());assertEquals(original.description(),copy.description());assertEquals(2,copy.nodes().size());assertEquals(1,copy.edges().size());assertNotEquals(original.roadmapId(),copy.roadmapId());
  for(var m:a.nodes()){assertNotEquals(m.sourceId(),m.personalId());assertEquals(7,m.personalId().version());assertEquals(original.nodes().stream().filter(n->n.nodeId().equals(m.sourceId())).findFirst().orElseThrow().title(),copy.nodes().stream().filter(n->n.nodeId().equals(m.personalId())).findFirst().orElseThrow().title());}
  var mapping=new HashMap<UUID,UUID>();a.nodes().forEach(n->mapping.put(n.sourceId(),n.personalId()));assertEquals(mapping.get(original.edges().getFirst().sourceNodeId()),copy.edges().getFirst().sourceNodeId());assertEquals(mapping.get(original.edges().getFirst().targetNodeId()),copy.edges().getFirst().targetNodeId());assertNotEquals(original.edges().getFirst().edgeId(),copy.edges().getFirst().edgeId());
  assertEquals(a,store.source(space,a.roadmapId()).adoption());assertEquals(original,published.get(sourceSpace,original.roadmapId()));assertEquals(1,events(a.adoptionId()));assertEquals(1,events(a.roadmapId()));
  var wire=sql.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,a.adoptionId());var env=RoadmapAdoptionEnvelopeV1.parseFrom(wire);var payload=RoadmapAdoptedV1.parseFrom(env.getPayload());
  assertEquals(space.toString(),env.getSpaceId());assertEquals(actor.toString(),env.getActorId());assertEquals(trace,env.getTraceId());assertEquals("RoadmapAdopted",env.getEventType());assertEquals("PRIVATE",payload.getVisibility());assertEquals(1,payload.getSourceVersion());assertEquals(2,payload.getNodesCount());assertEquals(a.createdAt(),payload.getCreatedAt());
  var graphEvent=RoadmapEventEnvelopeV1.parseFrom(sql.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,a.roadmapId()));assertEquals("RoadmapCreated",graphEvent.getEventType());assertEquals(2,RoadmapChangedPayloadV1.parseFrom(graphEvent.getPayload()).getNodesCount());
 }
 @Test void retriesAndClaimedActorKeepIdentityEvenAfterPublicUpdateAndPersonalRetirement(){
  var v=publish();var a=store.adopt(space,sourceSpace,v.roadmapId(),1,actor,trace).adoption();update(v);
  var repeat=store.adopt(space,sourceSpace,v.roadmapId(),1,reviewer,trace);assertFalse(repeat.created());assertEquals(a,repeat.adoption());
  status(409,()->store.adopt(actor,sourceSpace,v.roadmapId(),1,actor,trace));status(404,()->store.source(actor,a.roadmapId()));status(404,()->store.adopt(space,actor,v.roadmapId(),1,actor,trace));
  personal.metadata(space,a.roadmapId(),reviewer,1,null,null,true,trace);assertEquals(a,store.adopt(space,sourceSpace,v.roadmapId(),1,reviewer,trace).adoption());assertEquals("RETIRED",personal.inspect(space,a.roadmapId()).state());
  var fresh=store.adopt(space,sourceSpace,v.roadmapId(),2,reviewer,trace).adoption();assertNotEquals(a.roadmapId(),fresh.roadmapId());assertEquals("New public title",personal.get(space,fresh.roadmapId()).title());assertEquals(1,events(a.adoptionId()));
 }
 @Test void personalEditsProgressAndOtherDestinationsStayIndependent(){
  var v=publish();var a=store.adopt(space,sourceSpace,v.roadmapId(),1,actor,trace).adoption();var b=store.adopt(actor,sourceSpace,v.roadmapId(),1,actor,trace).adoption();
  personal.metadata(space,a.roadmapId(),actor,1,"My version","Personal",false,trace);assertEquals(v,published.get(sourceSpace,v.roadmapId()));assertEquals(v.title(),personal.get(actor,b.roadmapId()).title());assertEquals(a,store.source(space,a.roadmapId()).adoption());
  var studies=new LearningStudyStore(ds,Clock.systemUTC(),personal);
  assertEquals(0,studies.summary(space,a.roadmapId()).completed());
  assertEquals(0,studies.summary(actor,b.roadmapId()).completed());
  assertNull(store.source(space,personal.create(space,actor,"Ordinary","",trace).roadmapId()).adoption());
 }
 @Test void concurrentAdoptionProducesOneCopyAndOneProvenanceEvent()throws Exception{
  var v=publish();try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var barrier=new java.util.concurrent.CyclicBarrier(2);
   var calls=new ArrayList<java.util.concurrent.Future<RoadmapAdoptionStore.Result>>();for(int n=0;n<2;n++)calls.add(pool.submit(()->{barrier.await();return store.adopt(space,sourceSpace,v.roadmapId(),1,actor,trace);}));
   var a=calls.get(0).get();var b=calls.get(1).get();assertNotEquals(a.created(),b.created());assertEquals(a.adoption(),b.adoption());assertEquals(1,events(a.adoption().adoptionId()));assertEquals(1,events(a.adoption().roadmapId()));
  }assertEquals(1,personal.list(space,null,20).items().size());
 }
 @Test void adoptionOutboxFailureRollsBackGraphRevisionNodesAndSource(){
  var v=publish();String constraint="adoption_fail_"+space.toString().replace("-","");
  sql.execute("ALTER TABLE outbox_event ADD CONSTRAINT "+constraint+" CHECK (aggregatetype <> 'roadmap-adoption' OR trace_id <> '"+trace+"') NOT VALID");
  try{assertThrows(RuntimeException.class,()->store.adopt(space,sourceSpace,v.roadmapId(),1,actor,trace));}finally{sql.execute("ALTER TABLE outbox_event DROP CONSTRAINT "+constraint);}
  assertTrue(personal.list(space,null,20).items().isEmpty());assertEquals(0,sql.queryForObject("SELECT count(*) FROM roadmap_adoption WHERE space_id=?",Integer.class,space));
  assertEquals(v,published.get(sourceSpace,v.roadmapId()));assertTrue(store.adopt(space,sourceSpace,v.roadmapId(),1,actor,trace).created());
 }
}
