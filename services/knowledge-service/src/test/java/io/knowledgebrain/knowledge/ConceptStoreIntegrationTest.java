package io.knowledgebrain.knowledge;
import io.knowledgebrain.contracts.knowledge.v1.*;
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
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class ConceptStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;ConceptStore store;UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="c".repeat(32);
    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new ConceptStore(source,Clock.systemUTC());
    }
    @Test void preservesDescriptionRevisionsAndRetiredHistoryAcrossStoreRestart() throws Exception {
        UUID space=ids.next(),actor=ids.next();String description="# 공부\n[[Wiki Link]] <script>alert(1)</script>";
        var note=store.create(space,actor,"제목",description,trace);assertThat(note.visibility()).isEqualTo("PRIVATE");assertThat(note.conceptId().version()).isEqualTo(7);
        var updated=store.change(space,note.conceptId(),actor,1,"두 번째","**내용**",false,trace);assertThat(updated.version()).isEqualTo(2);
        store=new ConceptStore(source,Clock.systemUTC());assertThat(store.get(space,note.conceptId()).description()).isEqualTo("**내용**");
        store.change(space,note.conceptId(),actor,2,null,null,true,trace);
        assertThatThrownBy(()->store.get(space,note.conceptId())).isInstanceOf(ResponseStatusException.class);
        var revisions=store.history(space,note.conceptId(),Long.MAX_VALUE,10);assertThat(revisions).hasSize(3);assertThat(revisions.getLast().description()).isEqualTo(description);assertThat(revisions.getFirst().state()).isEqualTo("RETIRED");
        assertThat(store.list(space,null,20).items()).isEmpty();
        var events=jdbc.query("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='concept' ORDER BY occurred_at,id",(rs,n)->{try{return EventEnvelopeV1.parseFrom(rs.getBytes(1));}catch(Exception error){throw new RuntimeException(error);}},note.conceptId());
        assertThat(events).extracting(EventEnvelopeV1::getEventType).containsExactly("ConceptCreated","ConceptUpdated","ConceptRetired");
        for(var event:events) { assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getSpaceId()).isEqualTo(space.toString());assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(ConceptChangedPayloadV1.parseFrom(event.getPayload()).getVisibility()).isEqualTo("PRIVATE"); }
    }
    @Test void concurrentEditorsHaveOneWinnerAndNoExtraRevisionOrEvent() throws Exception {
        UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"Original","",trace);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)tasks.add(workers.submit(()->{start.await();try {store.change(space,note.conceptId(),actor,1,"Winner","new",false,trace);return 200;}catch(ResponseStatusException error){return error.getStatusCode().value();}}));
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));assertThat(codes).containsOnly(200,412);assertThat(Collections.frequency(codes,200)).isEqualTo(1);
        }
        assertThat(store.history(space,note.conceptId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='concept'",Long.class,note.conceptId())).isEqualTo(2);
    }
    @Test void outboxFailureRollsBackMutationAndRevision() {
        UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"Before","body",trace);
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_knowledge_trace CHECK(trace_id <> 'dddddddddddddddddddddddddddddddd') NOT VALID");
        try { assertThatThrownBy(()->store.change(space,note.conceptId(),actor,1,"Lost","changed",false,"d".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(store.get(space,note.conceptId()).version()).isEqualTo(1);assertThat(store.history(space,note.conceptId(),Long.MAX_VALUE,10)).hasSize(1);
            UUID emptySpace=ids.next();assertThatThrownBy(()->store.create(emptySpace,actor,"Lost","body","d".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(store.list(emptySpace,null,20).items()).isEmpty();
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_knowledge_trace"); }
        assertThat(store.change(space,note.conceptId(),actor,1,"After","changed",false,trace).version()).isEqualTo(2);
    }
    @Test void spaceScopingKeysetAndRevisionPaginationDoNotLeakOtherSpace() {
        UUID space=ids.next(),other=ids.next(),actor=ids.next();var first=store.create(space,actor,"first","",trace);store.create(space,actor,"second","",trace);store.create(other,actor,"secret","",trace);
        var page=store.list(space,null,1);assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isNotNull();assertThat(store.list(space,page.nextAfter(),1).items()).hasSize(1);
        assertThatThrownBy(()->store.get(other,first.conceptId())).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.change(other,first.conceptId(),actor,1,"hack","",false,trace)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.history(other,first.conceptId(),100,10)).isInstanceOf(ResponseStatusException.class);
        store.change(space,first.conceptId(),actor,1,"new","",false,trace);assertThat(store.history(space,first.conceptId(),2,1).getFirst().version()).isEqualTo(1);
    }
    @Test void referenceLookupAllowsSamePrivateOrActivePublicWithoutForeignText() {
        UUID destination=ids.next(),foreign=ids.next(),publicSpace=ids.next(),actor=ids.next();
        var own=store.create(destination,actor,"Own","",trace);
        var secret=store.create(foreign,actor,"Foreign private secret","private body",trace);
        UUID publicId=ids.next();store.createWithId(publicSpace,publicId,actor,"Published","public body",trace,"PUBLIC");
        assertThat(store.reference(destination,own.conceptId())).isEqualTo(new ConceptStore.Reference(destination,"PRIVATE"));
        assertThat(store.reference(destination,publicId)).isEqualTo(new ConceptStore.Reference(publicSpace,"PUBLIC"));
        assertThatThrownBy(()->store.reference(destination,secret.conceptId())).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.reference(destination,ids.next())).isInstanceOf(ResponseStatusException.class);
        store.changeScoped(publicSpace,publicId,actor,1,null,null,true,trace,"PUBLIC");
        assertThatThrownBy(()->store.reference(destination,publicId)).isInstanceOf(ResponseStatusException.class);
        store.change(destination,own.conceptId(),actor,1,null,null,true,trace);
        assertThatThrownBy(()->store.reference(destination,own.conceptId())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void unicodeSizeInvalidIdentifiersAndPrivacyAreEnforced() {
        UUID space=ids.next(),actor=ids.next();assertThatThrownBy(()->store.create(space,actor,"a","가".repeat(11000),trace)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.create(space,actor," ","",trace)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.create(UUID.randomUUID(),actor,"a","",trace)).isInstanceOf(ResponseStatusException.class);
        var note=store.create(space,actor,"a","",trace);assertThatThrownBy(()->jdbc.update("UPDATE concept SET visibility='INVALID' WHERE concept_id=?",note.conceptId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
