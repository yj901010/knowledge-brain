package io.knowledgebrain.learning;

import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_DATABASE_URL",matches=".+")
class RoadmapGuidanceStoreIntegrationTest {
 Clock clock=Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"),ZoneOffset.UTC);String trace="e".repeat(32);UuidV7 ids=new UuidV7(clock);DataSource ds;JdbcTemplate sql;RoadmapStore roadmaps;LearningStudyStore study;RoadmapGuidanceStore store;UUID space,actor,roadmap,a,b,c;RoadmapStore.Roadmap root;
 @BeforeEach void setup(){var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_LEARNING_DATABASE_URL"));source.setUser("brain_learning");source.setPassword(System.getenv("BRAIN_TEST_LEARNING_DATABASE_PASSWORD"));ds=source;Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();sql=new JdbcTemplate(ds);roadmaps=new RoadmapStore(ds,clock);study=new LearningStudyStore(ds,clock,roadmaps);store=new RoadmapGuidanceStore(ds);space=ids.next();actor=ids.next();root=roadmaps.create(space,actor,"Guidance","",trace);roadmap=root.roadmapId();root=roadmaps.graph(space,roadmap,actor,1,new RoadmapGraph.Edit(List.of(new RoadmapGraph.NodeEdit("new:a","First",""),new RoadmapGraph.NodeEdit("new:b","Second",""),new RoadmapGraph.NodeEdit("new:c","Third","")),List.of(new RoadmapGraph.EdgeEdit("new:ab","PREREQUISITE_OF","new:a","new:b"),new RoadmapGraph.EdgeEdit("new:bc","REQUIRED_FOR","new:b","new:c"))),trace);a=node("First");b=node("Second");c=node("Third");}
 UUID node(String title){return root.nodes().stream().filter(n->n.title().equals(title)).findFirst().orElseThrow().nodeId();}
 static List<UUID> ordered(UUID... values){return Arrays.stream(values).sorted(Comparator.comparing(UUID::toString)).toList();}
 String key(){return UUID.randomUUID().toString().replace("-","");}
 List<Long> counts(){return List.of(sql.queryForObject("SELECT count(*) FROM roadmap WHERE roadmap_id=?",Long.class,roadmap),sql.queryForObject("SELECT count(*) FROM roadmap_revision WHERE roadmap_id=?",Long.class,roadmap),sql.queryForObject("SELECT count(*) FROM roadmap_node WHERE roadmap_id=?",Long.class,roadmap),sql.queryForObject("SELECT count(*) FROM roadmap_edge WHERE roadmap_id=?",Long.class,roadmap),sql.queryForObject("SELECT count(*) FROM node_progress WHERE roadmap_id=?",Long.class,roadmap),sql.queryForObject("SELECT count(*) FROM node_progress_revision v JOIN node_progress p ON p.progress_id=v.progress_id WHERE p.roadmap_id=?",Long.class,roadmap),sql.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? OR aggregateid IN(SELECT progress_id FROM node_progress WHERE roadmap_id=?)",Long.class,roadmap,roadmap));}
 @Test void readUsesExactlyOneSelectAndNeverPersistsMissingProgressOrChangesRevisionOutbox(){
  var count=new CountingDataSource(ds);var reader=new RoadmapGuidanceStore(count);var before=counts();var guidance=reader.read(space,roadmap);assertEquals(1,count.statements.size());assertTrue(count.statements.getFirst().stripLeading().startsWith("SELECT "));assertEquals(2,guidance.roadmapVersion());assertEquals(List.of(a),RoadmapGuidanceRulesTest.ids(guidance.suggestions()));assertEquals(ordered(b,c),RoadmapGuidanceRulesTest.ids(guidance.waiting()));assertTrue(guidance.basis().stream().allMatch(p->p.status().equals("NOT_STARTED")&&p.progressVersion()==0));assertEquals(before,counts());assertEquals(root,roadmaps.get(space,roadmap));
 }
 @Test void manualProgressRemainsUnrestrictedAndDirectOnlyWithIndependentVersions(){
  var completed=study.progress(space,roadmap,b,actor,0,"COMPLETED",trace);var before=counts();var result=store.read(space,roadmap);assertEquals(ordered(a,c),RoadmapGuidanceRulesTest.ids(result.suggestions()));assertTrue(result.waiting().isEmpty());assertEquals(1,result.basis().stream().filter(p->p.nodeId().equals(b)).findFirst().orElseThrow().progressVersion());assertEquals(2,result.roadmapVersion());assertEquals(before,counts());assertEquals(completed,study.progress(space,roadmap,b));assertEquals(root,roadmaps.get(space,roadmap));
 }
 @Test void sessionsAndEvidenceNeverInferPriorityCompletionOrChangeGuidance(){
  var before=store.read(space,roadmap);var session=study.record(space,roadmap,actor,key(),new LearningStudyStore.Input(a,"2026-10-15T08:00:00Z","2026-10-15T09:00:00Z","Studied; manually tracked independently"),trace).session();var note=ids.next();var evidence=new LearningEvidenceStore(ds,clock,roadmaps);var linked=evidence.record(space,roadmap,actor,key(),new LearningEvidenceStore.Input(a,note,1,"Evidence is independent"),trace,()->new NoteReferenceClient.Reference(space,note,actor,1,"Note","PRIVATE")).evidence();assertEquals(before,store.read(space,roadmap));study.changeSession(space,roadmap,session.sessionId(),actor,1,null,true,trace);evidence.change(space,roadmap,linked.evidenceId(),actor,1,null,true,trace);assertEquals(before,store.read(space,roadmap));assertEquals(0,study.progress(space,roadmap,a).version());
 }
 @Test void savedCurrentGraphExcludesRetiredNodesWhileProgressHistoryRemainsIntact(){
  var completed=study.progress(space,roadmap,a,actor,0,"COMPLETED",trace);var retained=root.nodes().stream().filter(n->n.nodeId().equals(c)).findFirst().orElseThrow();roadmaps.graph(space,roadmap,actor,2,new RoadmapGraph.Edit(List.of(new RoadmapGraph.NodeEdit(retained.nodeId().toString(),"Saved new title",retained.description())),List.of()),trace);var result=store.read(space,roadmap);assertEquals(3,result.roadmapVersion());assertEquals(List.of(c),result.basis().stream().map(RoadmapGuidanceRules.Basis::nodeId).toList());assertEquals("Saved new title",result.suggestions().getFirst().title());assertTrue(result.waiting().isEmpty());assertEquals(completed,study.progressHistory(space,roadmap,a,Long.MAX_VALUE,5).getFirst().snapshot());
 }
 @Test void foreignMissingAndRetiredRootsAreUnavailableWithoutMutation(){
  var before=counts();for(UUID other:List.of(actor,ids.next()))assertEquals(404,assertThrows(ResponseStatusException.class,()->store.read(other,roadmap)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->store.read(space,ids.next())).getStatusCode().value());assertEquals(before,counts());roadmaps.metadata(space,roadmap,actor,2,null,null,true,trace);assertEquals(404,assertThrows(ResponseStatusException.class,()->store.read(space,roadmap)).getStatusCode().value());
 }
 @Test void corruptGraphRevisionVersionMetadataAndCanonicalHeadsFailClosed(){
  String original=JsonMapper.builder().build().writeValueAsString(root);
  for(String patch:List.of("jsonb_set(snapshot,'{version}','999')","jsonb_set(snapshot,'{visibility}','\"PUBLIC\"')","jsonb_set(snapshot,'{state}','\"RETIRED\"')","jsonb_set(snapshot,'{nodes,0,title}','\"Changed outside canonical head\"')")){
   sql.update("UPDATE roadmap_revision SET snapshot="+patch+" WHERE roadmap_id=? AND version=2",roadmap);assertThrows(IllegalStateException.class,()->store.read(space,roadmap));sql.update("UPDATE roadmap_revision SET snapshot=?::jsonb WHERE roadmap_id=? AND version=2",original,roadmap);
  }
  sql.update("UPDATE roadmap_node SET state='RETIRED' WHERE roadmap_id=? AND node_id=?",roadmap,a);assertThrows(IllegalStateException.class,()->store.read(space,roadmap));sql.update("UPDATE roadmap_node SET state='ACTIVE' WHERE roadmap_id=? AND node_id=?",roadmap,a);sql.update("UPDATE roadmap_edge SET state='RETIRED' WHERE roadmap_id=?",roadmap);assertThrows(IllegalStateException.class,()->store.read(space,roadmap));
 }
 @Test void corruptAndMissingCurrentProgressRevisionsFailClosed(){
  var p=study.progress(space,roadmap,a,actor,0,"IN_PROGRESS",trace);String original=JsonMapper.builder().build().writeValueAsString(p);
  for(String patch:List.of("jsonb_set(snapshot,'{version}','999')","jsonb_set(snapshot,'{status}','\"COMPLETED\"')","jsonb_set(snapshot,'{spaceId}','\""+actor+"\"')","jsonb_set(snapshot,'{visibility}','\"PUBLIC\"')")){
   sql.update("UPDATE node_progress_revision SET snapshot="+patch+" WHERE progress_id=? AND version=1",p.progressId());assertThrows(IllegalStateException.class,()->store.read(space,roadmap));sql.update("UPDATE node_progress_revision SET snapshot=?::jsonb WHERE progress_id=? AND version=1",original,p.progressId());
  }
  sql.update("DELETE FROM node_progress_revision WHERE progress_id=? AND version=1",p.progressId());assertThrows(IllegalStateException.class,()->store.read(space,roadmap));
 }
 @Test void oneStatementSnapshotNeverMixesConcurrentGraphAndProgressCommits()throws Exception{
  study.progress(space,roadmap,a,actor,0,"IN_PROGRESS",trace);var transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));var gate=new CountDownLatch(1);
  try(var pool=Executors.newSingleThreadExecutor()){
   var writer=pool.submit(()->{gate.await();for(int step=1;step<=20;step++){final int tick=step;transaction.execute(s->{roadmaps.metadata(space,roadmap,actor,1+tick,"Tick "+tick,"",false,trace);study.progress(space,roadmap,a,actor,tick,tick%2==1?"COMPLETED":"IN_PROGRESS",trace);return null;});}return true;});gate.countDown();
   for(int n=0;n<64;n++){var result=store.read(space,roadmap);var progress=result.basis().stream().filter(p->p.nodeId().equals(a)).findFirst().orElseThrow();assertEquals(result.roadmapVersion()-1,progress.progressVersion());assertEquals(progress.progressVersion()%2==0?"COMPLETED":"IN_PROGRESS",progress.status());}assertTrue(writer.get(30,TimeUnit.SECONDS));
  }
 }
 @Test void originalCompactNearLimitSnapshotsRemainReadableAfterJsonbFormatting(){
  var nodes=new ArrayList<RoadmapGraph.Node>();for(int n=0;n<100;n++)nodes.add(new RoadmapGraph.Node(ids.next(),"\"".repeat(200),"\n".repeat(1800)));
  var edges=new ArrayList<RoadmapGraph.Edge>();outer:for(int from=0;from<100;from++)for(int to=from+1;to<100;to++){edges.add(new RoadmapGraph.Edge(ids.next(),"PREREQUISITE_OF",nodes.get(from).nodeId(),nodes.get(to).nodeId()));if(edges.size()==500)break outer;}
  var json=JsonMapper.builder().build();var template=new RoadmapStore.Roadmap(ids.next(),space,"PRIVATE","ACTIVE",1,"Boundary","",clock.instant().toString(),clock.instant().toString(),nodes,edges);int base=json.writeValueAsBytes(template).length;int padding=(524200-base)/2;assertTrue(padding>=0&&padding<=32768,"Original graph must fit existing text bounds");
  var large=roadmaps.adoptGraph(space,actor,"Boundary","\n".repeat(padding),nodes,edges,trace);int compact=json.writeValueAsBytes(large).length;assertTrue(compact<=524288&&compact>=524000);assertTrue(sql.queryForObject("SELECT octet_length(snapshot::text) FROM roadmap_revision WHERE roadmap_id=? AND version=1",Integer.class,large.roadmapId())>524288);
  var before=sql.queryForObject("SELECT count(*) FROM node_progress WHERE roadmap_id=?",Integer.class,large.roadmapId());var result=store.read(space,large.roadmapId());assertEquals(100,result.basis().size());assertEquals(100,result.suggestions().size()+result.waiting().size());assertEquals(before,sql.queryForObject("SELECT count(*) FROM node_progress WHERE roadmap_id=?",Integer.class,large.roadmapId()));
 }
 static final class CountingDataSource extends DelegatingDataSource {
  final List<String> statements=new ArrayList<>();CountingDataSource(DataSource target){super(target);}
  @Override public Connection getConnection()throws SQLException{return wrap(super.getConnection());}
  @Override public Connection getConnection(String username,String password)throws SQLException{return wrap(super.getConnection(username,password));}
  private Connection wrap(Connection connection){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{if(method.getName().equals("prepareStatement"))statements.add((String)args[0]);try{return method.invoke(connection,args);}catch(InvocationTargetException e){throw e.getCause();}});}
 }
}
