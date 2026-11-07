package io.knowledgebrain.content;
import io.knowledgebrain.contracts.content.v1.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class NoteStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="c".repeat(32);
    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,Clock.systemUTC());
    }
    @Test void preservesMarkdownRevisionsAndRetiredHistoryAcrossStoreRestart() throws Exception {
        UUID space=ids.next(),actor=ids.next();String markdown="# 공부\n[[Wiki Link]] <script>alert(1)</script>";
        var note=store.create(space,actor,"제목",markdown,trace);assertThat(note.visibility()).isEqualTo("PRIVATE");assertThat(note.noteId().version()).isEqualTo(7);
        var updated=store.change(space,note.noteId(),actor,1,"두 번째","**내용**",false,trace);assertThat(updated.version()).isEqualTo(2);
        store=new NoteStore(source,Clock.systemUTC());assertThat(store.get(space,note.noteId()).markdown()).isEqualTo("**내용**");
        store.change(space,note.noteId(),actor,2,null,null,true,trace);
        assertThatThrownBy(()->store.get(space,note.noteId())).isInstanceOf(ResponseStatusException.class);
        var revisions=store.history(space,note.noteId(),Long.MAX_VALUE,10);assertThat(revisions).hasSize(3);assertThat(revisions.getLast().markdown()).isEqualTo(markdown);assertThat(revisions.getFirst().state()).isEqualTo("RETIRED");
        assertThat(store.list(space,null,20).items()).isEmpty();
        var events=jdbc.query("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='note' ORDER BY occurred_at,id",(rs,n)->{try{return EventEnvelopeV1.parseFrom(rs.getBytes(1));}catch(Exception error){throw new RuntimeException(error);}},note.noteId());
        assertThat(events).extracting(EventEnvelopeV1::getEventType).containsExactly("NoteCreated","NoteUpdated","NoteRetired");
        for(var event:events) { assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getSpaceId()).isEqualTo(space.toString());assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(NoteChangedPayloadV1.parseFrom(event.getPayload()).getVisibility()).isEqualTo("PRIVATE"); }
    }
    @Test void concurrentEditorsHaveOneWinnerAndNoExtraRevisionOrEvent() throws Exception {
        UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"Original","",trace);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)tasks.add(workers.submit(()->{start.await();try {store.change(space,note.noteId(),actor,1,"Winner","new",false,trace);return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));assertThat(codes).containsOnly(200,412);assertThat(Collections.frequency(codes,200)).isEqualTo(1);
        }
        assertThat(store.history(space,note.noteId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='note'",Long.class,note.noteId())).isEqualTo(2);
    }
    @Test void outboxFailureRollsBackMutationAndRevision() {
        UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"Before","body",trace);
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_content_trace CHECK(trace_id <> 'dddddddddddddddddddddddddddddddd') NOT VALID");
        try { assertThatThrownBy(()->store.change(space,note.noteId(),actor,1,"Lost","changed",false,"d".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(store.get(space,note.noteId()).version()).isEqualTo(1);assertThat(store.history(space,note.noteId(),Long.MAX_VALUE,10)).hasSize(1);
            UUID emptySpace=ids.next();assertThatThrownBy(()->store.create(emptySpace,actor,"Lost","body","d".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(store.list(emptySpace,null,20).items()).isEmpty();
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_content_trace"); }
        assertThat(store.change(space,note.noteId(),actor,1,"After","changed",false,trace).version()).isEqualTo(2);
    }
    @Test void spaceScopingKeysetAndRevisionPaginationDoNotLeakOtherSpace() {
        UUID space=ids.next(),other=ids.next(),actor=ids.next();var first=store.create(space,actor,"first","",trace);store.create(space,actor,"second","",trace);store.create(other,actor,"secret","",trace);
        var page=store.list(space,null,1);assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isNotNull();assertThat(store.list(space,page.nextAfter(),1).items()).hasSize(1);
        assertThatThrownBy(()->store.get(other,first.noteId())).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.change(other,first.noteId(),actor,1,"hack","",false,trace)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.history(other,first.noteId(),100,10)).isInstanceOf(ResponseStatusException.class);
        store.change(space,first.noteId(),actor,1,"new","",false,trace);assertThat(store.history(space,first.noteId(),2,1).getFirst().version()).isEqualTo(1);
    }
    @Test void unicodeSizeInvalidIdentifiersAndPrivacyAreEnforced() {
        UUID space=ids.next(),actor=ids.next();assertThatThrownBy(()->store.create(space,actor,"a","가".repeat(11000),trace)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.create(space,actor," ","",trace)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.create(UUID.randomUUID(),actor,"a","",trace)).isInstanceOf(ResponseStatusException.class);
        var note=store.create(space,actor,"a","",trace);assertThatThrownBy(()->jdbc.update("UPDATE note SET visibility='PUBLIC' WHERE note_id=?",note.noteId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
