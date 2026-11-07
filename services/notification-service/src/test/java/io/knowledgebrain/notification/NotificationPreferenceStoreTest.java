package io.knowledgebrain.notification;
import org.postgresql.ds.PGSimpleDataSource;import org.springframework.jdbc.core.JdbcTemplate;import org.flywaydb.core.Flyway;import org.springframework.web.server.ResponseStatusException;
import java.util.*;import java.util.concurrent.*;import java.time.*;
import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.notification.policy.v1.*;
import io.knowledgebrain.contracts.planning.delivery.v1.*;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BRAIN_TEST_NOTIFICATION_DATABASE_URL",matches=".+")
class NotificationPreferenceStoreTest {
 PGSimpleDataSource ds;JdbcTemplate sql;NotificationStore store;String schema;UUID space,actor;
 PGSimpleDataSource owner(){var d=new PGSimpleDataSource();d.setURL(System.getenv("BRAIN_TEST_NOTIFICATION_DATABASE_URL"));d.setUser("brain_notification");d.setPassword(System.getenv("BRAIN_TEST_NOTIFICATION_DATABASE_PASSWORD"));return d;}
 @BeforeEach void init(){ds=owner();schema="notification_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(ds).execute("CREATE SCHEMA "+schema);ds.setCurrentSchema(schema);Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();sql=new JdbcTemplate(ds);store=new NotificationStore(ds,Clock.systemUTC());space=DispatchFixtures.ids.next();actor=DispatchFixtures.ids.next();}
 @AfterEach void clean(){sql.execute("DROP SCHEMA "+schema+" CASCADE");}
 NotificationStore.Notification create(){assertTrue(store.accept(DispatchFixtures.event(space,DispatchFixtures.ids.next())));return store.list(space,"ACTIVE","ALL",null,100).items().stream().max(Comparator.comparing(NotificationStore.Notification::deliveredAt)).orElseThrow();}
 int count(String table){return sql.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
 void status(int code,Runnable fn){assertEquals(code,assertThrows(ResponseStatusException.class,fn::run).getStatusCode().value());}

 @Test void defaultReadsAndNoopDoNotMutateAndStaleRetryCannotUndoAnotherChange(){
  var initial=store.preference(space);assertEquals(0,initial.version());assertTrue(initial.inAppEnabled());assertNull(initial.preferenceId());
  assertEquals(initial,store.preference(space,actor,0,true,"b".repeat(32)));assertEquals(0,count("notification_preference"));assertEquals(0,count("outbox_event"));
  var off=store.preference(space,actor,0,false,"b".repeat(32));assertEquals(1,off.version());
  assertEquals(off,store.preference(space,actor,1,false,"b".repeat(32)));status(412,()->store.preference(space,actor,0,true,"b".repeat(32)));
  var on=store.preference(space,actor,1,true,"b".repeat(32));assertEquals(off.preferenceId(),on.preferenceId());assertEquals(2,count("notification_preference_revision"));
  assertEquals(List.of(2L,1L),store.preferenceHistory(space,Long.MAX_VALUE,5).stream().map(NotificationStore.PreferenceRevision::version).toList());
  assertEquals(List.of(1L),store.preferenceHistory(space,2,1).stream().map(NotificationStore.PreferenceRevision::version).toList());assertTrue(store.preferenceHistory(actor,Long.MAX_VALUE,5).isEmpty());
 }
 @Test void mutedDeliveryPreservesPinsAndReplayNeverArrivesAfterReenable()throws Exception{
  var existing=create();var off=store.preference(space,actor,0,false,"b".repeat(32));var bytes=DispatchFixtures.event(space,DispatchFixtures.ids.next());
  assertFalse(store.accept(bytes));assertEquals(existing,store.get(space,existing.notificationId()));
  var suppression=store.suppressions(space,null,20).items().getFirst();assertEquals(off.preferenceId(),suppression.preferenceId());assertEquals(1,suppression.preferenceVersion());assertEquals("PREFERENCE_DISABLED",suppression.reason());
  assertEquals(DispatchEvent.decode(bytes).source(),suppression.source());assertEquals(1,count("notification_suppression"));
  var out=sql.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,suppression.suppressionId());var envelope=NotificationPolicyEnvelopeV1.parseFrom(out);
  assertEquals("NotificationDeliverySuppressed",envelope.getEventType());var payload=NotificationSuppressionPayloadV1.parseFrom(envelope.getPayload());assertArrayEquals(bytes,payload.getSourceDispatchEnvelope().toByteArray());assertEquals("a".repeat(32),envelope.getTraceId());
  store.preference(space,actor,1,true,"b".repeat(32));assertFalse(store.accept(bytes));var restart=new NotificationStore(ds,Clock.systemUTC());assertFalse(restart.accept(bytes));assertEquals(suppression,restart.suppressions(space,null,20).items().getFirst());assertEquals(1,count("notification"));assertTrue(restart.accept(DispatchFixtures.event(space,DispatchFixtures.ids.next())));assertEquals(2,count("notification"));
 }
 @Test void preferenceAndSuppressionOutboxFailureRollBackWholeTransaction(){
  sql.execute("CREATE FUNCTION fail_policy() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled policy failure'; END $$");sql.execute("CREATE TRIGGER fail_policy BEFORE INSERT ON outbox_event FOR EACH ROW EXECUTE FUNCTION fail_policy()");
  assertThrows(RuntimeException.class,()->store.preference(space,actor,0,false,"b".repeat(32)));assertEquals(0,store.preference(space).version());assertEquals(0,count("notification_preference_revision"));
  sql.execute("ALTER TABLE outbox_event DISABLE TRIGGER fail_policy");store.preference(space,actor,0,false,"b".repeat(32));sql.execute("ALTER TABLE outbox_event ENABLE TRIGGER fail_policy");
  var bytes=DispatchFixtures.event(space,DispatchFixtures.ids.next());assertThrows(RuntimeException.class,()->store.accept(bytes));assertEquals(0,count("notification_inbox"));assertEquals(0,count("notification_suppression"));
  sql.execute("ALTER TABLE outbox_event DISABLE TRIGGER fail_policy");assertFalse(store.accept(bytes));assertEquals(1,count("notification_suppression"));
 }
 @Test void changedEventCannotMoveSameDispatchBetweenMutedAndReceivedOutcomes()throws Exception{
  var bytes=DispatchFixtures.event(space,DispatchFixtures.ids.next());assertTrue(store.accept(bytes));store.preference(space,actor,0,false,"b".repeat(32));
  var changed=ReminderDeliveryEnvelopeV1.parseFrom(bytes).toBuilder().setEventId(DispatchFixtures.ids.next().toString()).build().toByteArray();assertThrows(IllegalArgumentException.class,()->store.accept(changed));assertEquals(1,count("notification_inbox"));assertEquals(0,count("notification_suppression"));
  var muted=DispatchFixtures.event(space,DispatchFixtures.ids.next());assertFalse(store.accept(muted));store.preference(space,actor,1,true,"b".repeat(32));
  var changedMuted=ReminderDeliveryEnvelopeV1.parseFrom(muted).toBuilder().setEventId(DispatchFixtures.ids.next().toString()).build().toByteArray();assertThrows(IllegalArgumentException.class,()->store.accept(changedMuted));assertEquals(2,count("notification_inbox"));assertEquals(1,count("notification"));
 }
 @Test void concurrentInitialWritesHaveOneWinnerAndMutedPagesCannotCrossSpaces()throws Exception{
  try(var pool=Executors.newFixedThreadPool(2)){var gate=new CyclicBarrier(2);Callable<Integer> write=()->{gate.await();try{store.preference(space,actor,0,false,"b".repeat(32));return 200;}catch(org.springframework.web.server.ResponseStatusException e){return e.getStatusCode().value();}};var a=pool.submit(write);var b=pool.submit(write);assertEquals(Set.of(200,412),Set.of(a.get(),b.get()));}
  for(int n=0;n<23;n++)assertFalse(store.accept(DispatchFixtures.event(space,DispatchFixtures.ids.next())));
  var page=store.suppressions(space,null,20);assertEquals(20,page.items().size());assertEquals(3,store.suppressions(space,page.nextAfter(),20).items().size());status(400,()->store.suppressions(actor,page.nextAfter(),20));status(400,()->store.list(space,"ACTIVE","ALL",page.nextAfter(),20));assertTrue(store.suppressions(actor,null,20).items().isEmpty());
 }
 @Test void racingToggleAndDispatchCommitExactlyOneStableOutcome()throws Exception{
  var bytes=DispatchFixtures.event(space,DispatchFixtures.ids.next());
  try(var pool=Executors.newFixedThreadPool(2)){var gate=new CyclicBarrier(2);var a=pool.submit(()->{gate.await();return store.accept(bytes);});var b=pool.submit(()->{gate.await();return store.preference(space,actor,0,false,"b".repeat(32));});boolean received=a.get();b.get();assertEquals(received?1:0,count("notification"));assertEquals(received?0:1,count("notification_suppression"));}
  assertFalse(store.accept(bytes));assertEquals(1,count("notification")+count("notification_suppression"));assertEquals(1,count("notification_inbox"));
 }
}
