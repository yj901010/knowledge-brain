package io.knowledgebrain.integration;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;import org.postgresql.ds.PGSimpleDataSource;import org.flywaydb.core.Flyway;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.web.server.ResponseStatusException;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_INTEGRATION_DATABASE_URL",matches=".+")
class GitHubSyncHistoryIntegrationTest {
 PGSimpleDataSource ds;JdbcTemplate sql;GitHubStore store;UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor;String trace="a".repeat(32),url="https://github.com/octocat/hello-world";
 GitHubClient.Repository source=new GitHubClient.Repository("1296269","octocat/Hello-World","https://github.com/octocat/Hello-World","main");
 @BeforeEach void setup(){ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_INTEGRATION_DATABASE_URL"));ds.setUser("brain_integration");ds.setPassword(System.getenv("BRAIN_TEST_INTEGRATION_DATABASE_PASSWORD"));Flyway.configure().dataSource(ds).load().migrate();sql=new JdbcTemplate(ds);store=new GitHubStore(ds,Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"),ZoneOffset.UTC));space=ids.next();actor=ids.next();}
 String key(){return UUID.randomUUID().toString().replace("-","");}
 GitHubStore.Repository connect(){return store.connect(space,actor,key(),url,source,trace).repository();}
 GitHubClient.Batch empty(){return new GitHubClient.Batch(source,List.of(),null,false);}
 int events(){return sql.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN (SELECT repository_id FROM github_repository WHERE space_id=? UNION SELECT commit_id FROM github_commit WHERE space_id=?)",Integer.class,space,space);}
 void status(int n,org.junit.jupiter.api.function.Executable call){assertEquals(n,assertThrows(ResponseStatusException.class,call).getStatusCode().value());}
 @Test void completedEmptyAndDedupeReceiptsSurviveReplayAndDisconnectWithoutReadMutations(){
  var repo=connect();assertTrue(store.syncs(space,repo.repositoryId(),null,20).items().isEmpty());
  var batch=new GitHubClient.Batch(source,List.of(new GitHubClient.Commit("a".repeat(40),"Public subject",null,"2020-01-01T00:00:00Z")),"a".repeat(40),false);String firstKey=key();
  var first=store.sync(space,repo.repositoryId(),actor,firstKey,1,batch,trace);var duplicate=store.sync(space,repo.repositoryId(),actor,key(),2,batch,trace);var empty=store.sync(space,repo.repositoryId(),actor,key(),3,empty(),trace);
  assertEquals(1,first.sync().importedCount());assertEquals(0,duplicate.sync().importedCount());assertEquals(1,duplicate.sync().scannedCount());assertEquals(0,empty.sync().scannedCount());
  assertTrue(store.sync(space,repo.repositoryId(),actor,firstKey,1,batch,trace).replay());status(412,()->store.sync(space,repo.repositoryId(),actor,key(),1,batch,trace));store.disconnect(space,repo.repositoryId(),actor,4,trace);
  int count=events();var before=store.get(space,repo.repositoryId());var commits=store.commits(space,repo.repositoryId(),null,100);var revisions=store.history(space,repo.repositoryId(),Long.MAX_VALUE,100);
  for(int i=0;i<3;i++){var result=store.syncs(space,repo.repositoryId(),null,20);assertEquals(List.of(empty.sync(),duplicate.sync(),first.sync()),result.items());assertNull(result.nextBefore());for(var value:result.items()){var json=tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(value);assertEquals(8,json.size());assertFalse(json.has("actorId")||json.has("requestKey")||json.has("expectedVersion"));}}
  assertEquals(before,store.get(space,repo.repositoryId()));assertEquals(commits,store.commits(space,repo.repositoryId(),null,100));assertEquals(revisions,store.history(space,repo.repositoryId(),Long.MAX_VALUE,100));assertEquals(count,events());assertEquals(store.syncs(space,repo.repositoryId(),null,20),new GitHubStore(ds,Clock.systemUTC()).syncs(space,repo.repositoryId(),null,20));
 }
 @Test void logicalVersionKeysetsStayStableAtEqualTimesWithConcurrentNewObservations(){
  var repo=connect();for(long version=1;version<=25;version++)store.sync(space,repo.repositoryId(),actor,key(),version,empty(),trace);
  var first=store.syncs(space,repo.repositoryId(),null,20);assertEquals(20,first.items().size());assertEquals(26,first.items().getFirst().repositoryVersion());assertEquals(7L,first.nextBefore());assertEquals(1,first.items().stream().map(GitHubStore.Receipt::observedAt).distinct().count());
  store.sync(space,repo.repositoryId(),actor,key(),26,empty(),trace);var second=store.syncs(space,repo.repositoryId(),first.nextBefore(),20);assertEquals(5,second.items().size());assertNull(second.nextBefore());assertEquals(List.of(6L,5L,4L,3L,2L),second.items().stream().map(GitHubStore.Receipt::repositoryVersion).toList());var seen=new HashSet<UUID>();for(var value:first.items())assertTrue(seen.add(value.syncId()));for(var value:second.items())assertTrue(seen.add(value.syncId()));assertEquals(25,seen.size());assertEquals(27,store.syncs(space,repo.repositoryId(),null,20).items().getFirst().repositoryVersion());assertNull(store.syncs(space,repo.repositoryId(),null,100).nextBefore());assertTrue(store.syncs(space,repo.repositoryId(),2L,20).items().isEmpty());
 }
 @Test void eachQueryUsesExactSpaceRepositoryAndValidatedBounds(){
  var repo=connect();store.sync(space,repo.repositoryId(),actor,key(),1,empty(),trace);var otherSource=new GitHubClient.Repository("42","a/b","https://github.com/a/b","main");var other=store.connect(space,actor,key(),"https://github.com/a/b",otherSource,trace).repository();assertTrue(store.syncs(space,other.repositoryId(),null,20).items().isEmpty());status(404,()->store.syncs(ids.next(),repo.repositoryId(),null,20));status(404,()->store.syncs(space,ids.next(),null,20));for(long before:new long[]{0,-1})status(400,()->store.syncs(space,repo.repositoryId(),before,20));for(int limit:new int[]{0,101})status(400,()->store.syncs(space,repo.repositoryId(),null,limit));assertEquals(1,store.syncs(space,repo.repositoryId(),null,1).items().size());
 }
}
