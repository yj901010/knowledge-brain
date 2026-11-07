package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.alias.v1.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class AliasStoreIntegrationTest {
    PGSimpleDataSource source;
    JdbcTemplate jdbc;
    AliasStore store;
    ConceptStore concepts;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    String trace="c".repeat(32);
    UUID space,actor,concept;

    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);store=new AliasStore(source,Clock.systemUTC());concepts=new ConceptStore(source,Clock.systemUTC());
        space=ids.next();actor=ids.next();concept=concepts.create(space,actor,"Java","Stable canonical description",trace).conceptId();
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }
    long eventCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='alias'",Long.class,id);
    }
    List<EventEnvelopeV1> events(UUID id) {
        return jdbc.query("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='alias' ORDER BY occurred_at,id",
            (rs,n)->{try{return EventEnvelopeV1.parseFrom(rs.getBytes(1));}catch(Exception e){throw new IllegalStateException(e);}},id);
    }

    @Test void revisionsEventsAndParentVersionRemainIndependentAcrossRestartAndRetirement() throws Exception {
        var parent=concepts.get(space,concept);var alias=store.create(space,concept,actor,"  자바  ",trace);
        assertThat(alias.aliasId().version()).isEqualTo(7);assertThat(alias.label()).isEqualTo("자바");
        assertThat(alias.visibility()).isEqualTo("PRIVATE");assertThat(alias.scope()).isEqualTo("SPACE");assertThat(alias.version()).isEqualTo(1);
        var initial=store.history(space,concept,alias.aliasId(),Long.MAX_VALUE,10).getFirst();
        assertThat(initial.revisionId().version()).isEqualTo(7);
        var updated=store.change(space,concept,alias.aliasId(),actor,1,"Java language",false,trace);assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.aliasId()).isEqualTo(alias.aliasId());assertThat(updated.createdAt()).isEqualTo(alias.createdAt());
        store=new AliasStore(source,Clock.systemUTC());assertThat(store.get(space,concept,alias.aliasId()).label()).isEqualTo("Java language");
        store.change(space,concept,alias.aliasId(),actor,2,null,true,trace);
        status(404,()->store.get(space,concept,alias.aliasId()));assertThat(store.list(space,concept,null,20).items()).isEmpty();
        var revisions=store.history(space,concept,alias.aliasId(),Long.MAX_VALUE,10);
        assertThat(revisions).extracting(AliasStore.Revision::version).containsExactly(3L,2L,1L);
        assertThat(revisions.getFirst().state()).isEqualTo("RETIRED");assertThat(revisions.getLast()).isEqualTo(initial);
        assertThat(concepts.get(space,concept)).isEqualTo(parent);
        assertThat(concepts.history(space,concept,Long.MAX_VALUE,10)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='concept'",Long.class,concept)).isEqualTo(1);
        var events=events(alias.aliasId());assertThat(events).extracting(EventEnvelopeV1::getEventType).containsExactly("AliasCreated","AliasUpdated","AliasRetired");
        for(int i=0;i<events.size();i++) {
            var event=events.get(i);var payload=AliasChangedPayloadV1.parseFrom(event.getPayload());
            assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getEventVersion()).isEqualTo(1);
            assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(event.getAggregateType()).isEqualTo("Alias");
            assertThat(event.getAggregateId()).isEqualTo(alias.aliasId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
            assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);
            assertThat(event.getOccurredAtEpochMillis()).isPositive();assertThat(payload.getAliasId()).isEqualTo(alias.aliasId().toString());
            assertThat(payload.getConceptId()).isEqualTo(concept.toString());assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
            assertThat(payload.getScope()).isEqualTo("SPACE");assertThat(payload.getVersion()).isEqualTo(i+1L);
            assertThat(payload.getRevisionId()).isEqualTo(revisions.get(2-i).revisionId().toString());
            assertThat(payload.getLabel()).isEqualTo(i==0?"자바":"Java language");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='alias' AND schema_group='knowledge' AND schema_artifact='alias-events' AND schema_version='1'",Long.class,alias.aliasId())).isEqualTo(3);
    }

    @Test void exactDuplicatesConflictWithoutEventsAndRetiredLabelsCanBeReused() {
        var first=store.create(space,concept,actor,"Java",trace);
        status(409,()->store.create(space,concept,actor,"  Java ",trace));
        var second=store.create(space,concept,actor,"java",trace);
        status(409,()->store.change(space,concept,second.aliasId(),actor,1,"Java",false,trace));
        assertThat(store.get(space,concept,second.aliasId()).label()).isEqualTo("java");assertThat(eventCount(second.aliasId())).isEqualTo(1);
        assertThat(store.history(space,concept,second.aliasId(),Long.MAX_VALUE,10)).hasSize(1);
        var other=concepts.create(space,actor,"Other", "",trace);
        assertThat(store.create(space,other.conceptId(),actor,"Java",trace).conceptId()).isEqualTo(other.conceptId());
        store.change(space,concept,first.aliasId(),actor,1,null,true,trace);
        var replacement=store.create(space,concept,actor,"Java",trace);
        assertThat(replacement.aliasId()).isNotEqualTo(first.aliasId());assertThat(replacement.version()).isEqualTo(1);
        assertThat(store.history(space,concept,first.aliasId(),Long.MAX_VALUE,10)).hasSize(2);
        assertThat(eventCount(first.aliasId())).isEqualTo(2);
    }

    @Test void concurrentEditorsAndConcurrentDuplicateCreatesHaveExactlyOneWinner() throws Exception {
        var alias=store.create(space,concept,actor,"Before",trace);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)tasks.add(workers.submit(()->{start.await();try{store.change(space,concept,alias.aliasId(),actor,1,"Winner",false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(200,412);assertThat(Collections.frequency(codes,200)).isEqualTo(1);
            var createStart=new CountDownLatch(1);tasks.clear();
            for(int i=0;i<4;i++)tasks.add(workers.submit(()->{createStart.await();try{store.create(space,concept,actor,"Unique concurrent",trace);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            createStart.countDown();codes.clear();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(201,409);assertThat(Collections.frequency(codes,201)).isEqualTo(1);
        }
        assertThat(eventCount(alias.aliasId())).isEqualTo(2);assertThat(store.history(space,concept,alias.aliasId(),Long.MAX_VALUE,10)).hasSize(2);
        assertThat(store.list(space,concept,null,20).items()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event o JOIN alias a ON a.alias_id=o.aggregateid WHERE a.space_id=? AND o.aggregatetype='alias'",Long.class,space)).isEqualTo(3);
    }

    @Test void outboxFailureRollsBackAliasAndImmutableRevisionTogether() {
        var alias=store.create(space,concept,actor,"Before",trace);
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_alias_trace CHECK(trace_id <> 'dddddddddddddddddddddddddddddddd') NOT VALID");
        try {
            assertThatThrownBy(()->store.change(space,concept,alias.aliasId(),actor,1,"Lost",false,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,concept,alias.aliasId()).label()).isEqualTo("Before");assertThat(store.get(space,concept,alias.aliasId()).version()).isEqualTo(1);
            assertThat(store.history(space,concept,alias.aliasId(),Long.MAX_VALUE,10)).hasSize(1);assertThat(eventCount(alias.aliasId())).isEqualTo(1);
            assertThatThrownBy(()->store.create(space,concept,actor,"Lost create","d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.list(space,concept,null,20).items()).hasSize(1);
            assertThatThrownBy(()->store.change(space,concept,alias.aliasId(),actor,1,null,true,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,concept,alias.aliasId()).state()).isEqualTo("ACTIVE");
        } finally {jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_alias_trace");}
        assertThat(store.change(space,concept,alias.aliasId(),actor,1,"After",false,trace).version()).isEqualTo(2);
    }

    @Test void spaceConceptScopingAndStableKeysetPaginationNeverExposeForeignAliases() {
        var first=store.create(space,concept,actor,"First",trace);store.create(space,concept,actor,"Second",trace);store.create(space,concept,actor,"Third",trace);
        UUID foreign=ids.next();var secretConcept=concepts.create(foreign,actor,"Foreign secret","",trace);
        var secret=store.create(foreign,secretConcept.conceptId(),actor,"Foreign alias secret",trace);
        var other=concepts.create(space,actor,"Another","",trace);store.create(space,other.conceptId(),actor,"Another alias",trace);
        var page=store.list(space,concept,null,1);assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isNotNull();
        var remaining=store.list(space,concept,page.nextAfter(),100);assertThat(remaining.items()).hasSize(2);assertThat(remaining.nextAfter()).isNull();
        var read=new HashSet<UUID>();page.items().forEach(a->read.add(a.aliasId()));remaining.items().forEach(a->read.add(a.aliasId()));
        assertThat(read).hasSize(3).doesNotContain(secret.aliasId());
        status(404,()->store.get(foreign,concept,first.aliasId()));status(404,()->store.get(space,other.conceptId(),first.aliasId()));
        status(404,()->store.get(space,secretConcept.conceptId(),secret.aliasId()));status(404,()->store.list(space,secretConcept.conceptId(),null,20));
        status(404,()->store.history(foreign,concept,first.aliasId(),Long.MAX_VALUE,10));status(404,()->store.history(space,other.conceptId(),first.aliasId(),Long.MAX_VALUE,10));
        status(404,()->store.change(foreign,concept,first.aliasId(),actor,1,"Hack",false,trace));
        store.change(space,concept,first.aliasId(),actor,1,"Revised",false,trace);
        assertThat(store.history(space,concept,first.aliasId(),2,1)).extracting(AliasStore.Revision::version).containsExactly(1L);
        assertThat(store.get(foreign,secretConcept.conceptId(),secret.aliasId()).label()).isEqualTo("Foreign alias secret");
    }

    @Test void publicAndRetiredParentsAreUnavailableButPrivateAuditHistoryIsRetained() {
        UUID publicSpace=ids.next(),publicId=ids.next();concepts.createWithId(publicSpace,publicId,actor,"Public","",trace,"PUBLIC");
        status(404,()->store.create(publicSpace,publicId,actor,"Global alias",trace));status(404,()->store.list(publicSpace,publicId,null,20));
        var alias=store.create(space,concept,actor,"Before retire",trace);concepts.change(space,concept,actor,1,null,null,true,trace);
        status(404,()->store.get(space,concept,alias.aliasId()));status(404,()->store.list(space,concept,null,20));
        status(404,()->store.create(space,concept,actor,"New",trace));status(404,()->store.change(space,concept,alias.aliasId(),actor,1,"Changed",false,trace));
        status(404,()->store.change(space,concept,alias.aliasId(),actor,1,null,true,trace));
        var history=store.history(space,concept,alias.aliasId(),Long.MAX_VALUE,10);assertThat(history).hasSize(1);assertThat(history.getFirst().label()).isEqualTo("Before retire");
        assertThat(eventCount(alias.aliasId())).isEqualTo(1);
    }

    @Test void parentRetirementRacingWithCreateOrUpdateProducesNoWriteAfterRetirement() throws Exception {
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Future<Integer> creation=workers.submit(()->{start.await();try{store.create(space,concept,actor,"Race",trace);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            Future<Integer> retirement=workers.submit(()->{start.await();concepts.change(space,concept,actor,1,null,null,true,trace);return 204;});
            start.countDown();assertThat(creation.get(20,TimeUnit.SECONDS)).isIn(201,404);assertThat(retirement.get(20,TimeUnit.SECONDS)).isEqualTo(204);
            status(404,()->store.create(space,concept,actor,"After retirement",trace));
            var records=jdbc.query("SELECT alias_id FROM alias WHERE concept_id=?",(rs,n)->rs.getObject(1,UUID.class),concept);
            assertThat(records).hasSizeLessThanOrEqualTo(1);for(UUID id:records){status(404,()->store.get(space,concept,id));assertThat(eventCount(id)).isEqualTo(1);assertThat(store.history(space,concept,id,Long.MAX_VALUE,10)).hasSize(1);}
            UUID active=concepts.create(space,actor,"Update race","",trace).conceptId();var alias=store.create(space,active,actor,"Before",trace);
            var updateStart=new CountDownLatch(1);
            Future<Integer> update=workers.submit(()->{updateStart.await();try{store.change(space,active,alias.aliasId(),actor,1,"Changed",false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            Future<Integer> retire=workers.submit(()->{updateStart.await();concepts.change(space,active,actor,1,null,null,true,trace);return 204;});
            updateStart.countDown();int code=update.get(20,TimeUnit.SECONDS);assertThat(code).isIn(200,404);assertThat(retire.get(20,TimeUnit.SECONDS)).isEqualTo(204);
            status(404,()->store.change(space,active,alias.aliasId(),actor,code==200?2:1,"Late",false,trace));
            assertThat(store.history(space,active,alias.aliasId(),Long.MAX_VALUE,10)).hasSize(code==200?2:1);assertThat(eventCount(alias.aliasId())).isEqualTo(code==200?2:1);
        }
    }

    @Test void databaseConstraintsEnforcePrivateScopeTenantParentAndRevisionUniqueness() {
        var alias=store.create(space,concept,actor,"Guarded",trace);
        assertThatThrownBy(()->jdbc.update("UPDATE alias SET space_id=? WHERE alias_id=?",ids.next(),alias.aliasId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE alias SET visibility='PUBLIC' WHERE alias_id=?",alias.aliasId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE alias SET scope='GLOBAL' WHERE alias_id=?",alias.aliasId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE alias SET version=0 WHERE alias_id=?",alias.aliasId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE alias SET label=' ' WHERE alias_id=?",alias.aliasId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO alias_revision(revision_id,alias_id,version,state,label,actor_id,occurred_at) VALUES (?,?,1,'ACTIVE','Duplicate',?,now())",ids.next(),alias.aliasId(),actor)).isInstanceOf(DataAccessException.class);
        assertThat(store.get(space,concept,alias.aliasId()).label()).isEqualTo("Guarded");assertThat(eventCount(alias.aliasId())).isEqualTo(1);
    }

    @Test void invalidUnicodeIdentifiersCursorsAndLabelsCannotProduceAliases() {
        for(String label:List.of("","  ","a".repeat(201),"a\u0000b","\uD800","\uDC00"))status(400,()->store.create(space,concept,actor,label,trace));
        status(400,()->store.create(space,concept,actor,null,trace));
        status(400,()->store.create(UUID.randomUUID(),concept,actor,"a",trace));
        status(400,()->store.create(space,UUID.randomUUID(),actor,"a",trace));
        status(400,()->store.create(space,concept,UUID.randomUUID(),"a",trace));
        status(400,()->store.list(space,concept,UUID.randomUUID(),20));status(400,()->store.list(space,concept,null,0));status(400,()->store.list(space,concept,null,101));
        assertThat(store.list(space,concept,null,20).items()).isEmpty();
        var edge=store.create(space,concept,actor,"😀".repeat(200),trace);assertThat(edge.label().codePointCount(0,edge.label().length())).isEqualTo(200);
        status(400,()->store.history(space,concept,edge.aliasId(),0,1));status(400,()->store.history(space,concept,edge.aliasId(),Long.MAX_VALUE,11));
        status(400,()->store.change(space,concept,edge.aliasId(),actor,0,"New",false,trace));
        assertThat(eventCount(edge.aliasId())).isEqualTo(1);
    }

    @Test void activeAliasLimitIsAtomicAndRetirementRestoresOneSlot() throws Exception {
        for(int i=0;i<99;i++)store.create(space,concept,actor,"Alias "+i,trace);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<3;i++){String label="Last slot "+i;tasks.add(workers.submit(()->{start.await();try{store.create(space,concept,actor,label,trace);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));}
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(201,409);assertThat(Collections.frequency(codes,201)).isEqualTo(1);
        }
        var all=store.list(space,concept,null,100);assertThat(all.items()).hasSize(100);assertThat(all.nextAfter()).isNull();
        status(409,()->store.create(space,concept,actor,"Over capacity",trace));
        var first=all.items().getFirst();store.change(space,concept,first.aliasId(),actor,1,null,true,trace);
        assertThat(store.create(space,concept,actor,"Freed slot",trace).version()).isEqualTo(1);
        assertThat(store.list(space,concept,null,100).items()).hasSize(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event o JOIN alias a ON a.alias_id=o.aggregateid WHERE a.concept_id=? AND o.aggregatetype='alias'",Long.class,concept)).isEqualTo(102);
    }
}
