package io.knowledgebrain.content;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import io.knowledgebrain.contracts.content.daily.v1.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class DailyStoreIntegrationTest {
 PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="a".repeat(32);
 @BeforeEach void setup(){source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,Clock.systemUTC());}
 @Test void sameDateHasOneStableNoteAndReopenDoesNotOverwriteAnything(){
 UUID space=ids.next(),actor=ids.next();var first=store.openDaily(space,"2026-10-07","Asia/Seoul",actor,trace);assertThat(first.created()).isTrue();var note=first.entry().note();assertThat(note.title()).isEqualTo("2026-10-07");assertThat(note.markdown()).isEqualTo("# 2026-10-07\n");
 store.change(space,note.noteId(),actor,1,"Journal","[[Other]]",false,trace);store.metadata(space,note.noteId(),actor,0,new MetadataInput.Edit(List.of("study"),List.of(new MetadataInput.Field("done","BOOLEAN","true"))),trace);
 var reopen=store.openDaily(space,"2026-10-07","Asia/Seoul",actor,trace);assertThat(reopen.created()).isFalse();assertThat(reopen.entry().note().noteId()).isEqualTo(note.noteId());assertThat(reopen.entry().note().markdown()).isEqualTo("[[Other]]");assertThat(reopen.entry().note().version()).isEqualTo(2);assertThat(store.history(space,note.noteId(),100,10)).hasSize(2);assertThat(store.metadata(space,note.noteId()).tags()).hasSize(1);
 store=new NoteStore(source,Clock.systemUTC());assertThat(store.daily(space,"2026-10-07")).isEqualTo(reopen.entry());assertThatThrownBy(()->store.openDaily(space,"2026-10-07","UTC",actor,trace)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
 assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='daily-note'",Long.class,note.noteId())).isEqualTo(2);
 }
 @Test void parallelOpenCreatesOneNoteRevisionBindingAndDailyEvent() throws Exception {
 UUID space=ids.next(),actor=ids.next();var results=new ArrayList<DailyNoteStore.Opened>();try(var workers=Executors.newVirtualThreadPerTaskExecutor()){var start=new CountDownLatch(1);var futures=new ArrayList<Future<DailyNoteStore.Opened>>();for(int i=0;i<6;i++)futures.add(workers.submit(()->{start.await();return store.openDaily(space,"2026-10-07","Asia/Seoul",actor,trace);}));start.countDown();for(var f:futures)results.add(f.get(20,TimeUnit.SECONDS));}
 assertThat(results.stream().filter(DailyNoteStore.Opened::created).count()).isEqualTo(1);assertThat(results.stream().map(x->x.entry().note().noteId()).distinct().count()).isEqualTo(1);var note=results.getFirst().entry().note();assertThat(store.list(space,null,100).items()).hasSize(1);assertThat(store.history(space,note.noteId(),100,10)).hasSize(1);assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='daily-note'",Long.class,note.noteId())).isEqualTo(1);
 }
 @Test void datesAndSpacesScopeDescendingPaginationRangeAndRetirement(){
 UUID space=ids.next(),actor=ids.next(),other=ids.next();var first=store.openDaily(space,"2026-10-07","Asia/Seoul",actor,trace);store.openDaily(space,"2026-10-08","Asia/Seoul",actor,trace);store.openDaily(space,"2026-10-09","UTC",actor,trace);var foreign=store.openDaily(other,"2026-10-07","Asia/Seoul",actor,trace);assertThat(foreign.entry().note().noteId()).isNotEqualTo(first.entry().note().noteId());
 var page=store.dailyList(space,null,null,null,1);assertThat(page.items().getFirst().date()).isEqualTo(LocalDate.of(2026,10,9));assertThat(page.nextBefore()).isEqualTo(LocalDate.of(2026,10,9));assertThat(store.dailyList(space,null,null,page.nextBefore().toString(),1).items().getFirst().date()).isEqualTo(LocalDate.of(2026,10,8));assertThat(store.dailyList(space,"2026-10-07","2026-10-08",null,100).items()).hasSize(2);assertThatThrownBy(()->store.dailyList(space,"2026-10-09","2026-10-07",null,20)).isInstanceOf(ResponseStatusException.class);
 var note=first.entry().note();store.change(space,note.noteId(),actor,1,null,null,true,trace);assertThat(store.dailyList(space,null,null,null,100).items()).hasSize(2);assertThatThrownBy(()->store.daily(space,"2026-10-07")).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.openDaily(space,"2026-10-07","Asia/Seoul",actor,trace)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));assertThat(store.history(space,note.noteId(),100,10)).hasSize(2);assertThat(store.daily(other,"2026-10-07").note().state()).isEqualTo("ACTIVE");
 assertThatThrownBy(()->jdbc.update("UPDATE daily_note SET journal_date='2026-10-10' WHERE note_id=?",note.noteId())).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM daily_note WHERE note_id=?",note.noteId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void dailyOutboxFailureRollsBackNoteLinksRevisionAndDateReservation(){
 UUID space=ids.next(),actor=ids.next();jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT daily_validation_rollback CHECK(aggregatetype <> 'daily-note' OR trace_id <> 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb') NOT VALID");try{assertThatThrownBy(()->store.openDaily(space,"2026-10-07","Asia/Seoul",actor,"b".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(store.list(space,null,100).items()).isEmpty();assertThat(store.dailyList(space,null,null,null,100).items()).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_note WHERE space_id=?",Long.class,space)).isZero();}finally{jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT daily_validation_rollback");}
 assertThat(store.openDaily(space,"2026-10-07","Asia/Seoul",actor,trace).created()).isTrue();
 }
 @Test void todayReadIsPureUsesClockAndRetainsOriginalZoneInExistingEntry(){
 UUID space=ids.next(),actor=ids.next();store=new NoteStore(source,Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"),ZoneOffset.UTC));assertThat(store.dailyToday(space,"Asia/Seoul").date()).isEqualTo(LocalDate.of(2026,10,8));assertThat(store.dailyToday(space,"Asia/Seoul").entry()).isNull();assertThat(store.list(space,null,100).items()).isEmpty();var daily=store.openDaily(space,"2026-10-08","Pacific/Auckland",actor,trace);assertThat(store.dailyToday(space,"Asia/Seoul").entry().timezone()).isEqualTo("Pacific/Auckland");assertThat(store.dailyToday(space,"UTC").date()).isEqualTo(LocalDate.of(2026,10,7));assertThat(store.dailyToday(space,"UTC").entry()).isNull();
 }
}
