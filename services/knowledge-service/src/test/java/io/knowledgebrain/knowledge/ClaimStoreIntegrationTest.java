package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.claim.v2.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class ClaimStoreIntegrationTest {
    PGSimpleDataSource source;
    JdbcTemplate jdbc;
    ClaimStore store;
    ConceptStore concepts;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    String trace="c".repeat(32);
    UUID space,actor,subject,predicate,object;

    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);store=new ClaimStore(source,Clock.systemUTC());concepts=new ConceptStore(source,Clock.systemUTC());
        space=ids.next();actor=ids.next();subject=concept("Subject");predicate=concept("Predicate");object=concept("Object");
    }
    UUID concept(String title) {return concepts.create(space,actor,title,"",trace).conceptId();}
    ClaimStore.ObjectValue ref(UUID id) {return new ClaimStore.ObjectValue("CONCEPT",id);}
    ClaimStore.Claim create() {return store.create(space,subject,actor,predicate,ref(object),trace);}
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }
    long eventCount(UUID id) {return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='claim-v2'",Long.class,id);}
    long spaceEvents() {return jdbc.queryForObject("SELECT count(*) FROM outbox_event o JOIN claim c ON c.claim_id=o.aggregateid WHERE c.space_id=? AND o.aggregatetype='claim-v2'",Long.class,space);}
    List<EventEnvelopeV2> events(UUID id) {
        return jdbc.query("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='claim-v2' ORDER BY occurred_at,id",
            (rs,n)->{try{return EventEnvelopeV2.parseFrom(rs.getBytes(1));}catch(Exception e){throw new IllegalStateException(e);}},id);
    }

    @Test void immutableTripleRevisionsEventsAndConceptVersionsStayIndependentAcrossRestart() throws Exception {
        var parents=List.of(concepts.get(space,subject),concepts.get(space,predicate),concepts.get(space,object));
        var aliases=new AliasStore(source,Clock.systemUTC());var alias=aliases.create(space,subject,actor,"Independent alias",trace);
        var claim=create();assertThat(claim.claimId().version()).isEqualTo(7);assertThat(claim.version()).isEqualTo(1);
        assertThat(claim.subjectConceptId()).isEqualTo(subject);assertThat(claim.predicateConceptId()).isEqualTo(predicate);
        assertThat(claim.object()).isEqualTo(ref(object));assertThat(claim.visibility()).isEqualTo("PRIVATE");assertThat(claim.verificationState()).isEqualTo("DRAFT");
        var initial=store.history(space,subject,claim.claimId(),Long.MAX_VALUE,10).getFirst();assertThat(initial.revisionId().version()).isEqualTo(7);
        UUID nextPredicate=concept("Next predicate"),nextObject=concept("Next object");
        var updated=store.change(space,subject,claim.claimId(),actor,1,nextPredicate,ref(nextObject),false,trace);
        assertThat(updated.claimId()).isEqualTo(claim.claimId());assertThat(updated.createdAt()).isEqualTo(claim.createdAt());assertThat(updated.version()).isEqualTo(2);
        store=new ClaimStore(source,Clock.systemUTC());assertThat(store.get(space,subject,claim.claimId()).object()).isEqualTo(ref(nextObject));
        store.change(space,subject,claim.claimId(),actor,2,null,null,true,trace);
        status(404,()->store.get(space,subject,claim.claimId()));assertThat(store.list(space,subject,null,20).items()).isEmpty();
        var history=store.history(space,subject,claim.claimId(),Long.MAX_VALUE,10);
        assertThat(history).extracting(ClaimStore.Revision::version).containsExactly(3L,2L,1L);assertThat(history.getLast()).isEqualTo(initial);
        assertThat(history.getFirst().state()).isEqualTo("RETIRED");assertThat(history.getFirst().verificationState()).isEqualTo("DRAFT");
        assertThat(store.history(space,subject,claim.claimId(),3,1)).extracting(ClaimStore.Revision::version).containsExactly(2L);
        assertThat(List.of(concepts.get(space,subject),concepts.get(space,predicate),concepts.get(space,object))).isEqualTo(parents);
        assertThat(aliases.get(space,subject,alias.aliasId())).isEqualTo(alias);assertThat(aliases.history(space,subject,alias.aliasId(),Long.MAX_VALUE,10)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='alias'",Long.class,alias.aliasId())).isEqualTo(1);
        for(UUID id:List.of(subject,predicate,object,nextPredicate,nextObject)) {
            assertThat(concepts.get(space,id).version()).isEqualTo(1);assertThat(concepts.history(space,id,Long.MAX_VALUE,10)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='concept'",Long.class,id)).isEqualTo(1);
        }
        var events=events(claim.claimId());assertThat(events).extracting(EventEnvelopeV2::getEventType).containsExactly("ClaimCreated","ClaimUpdated","ClaimRetired");
        for(int i=0;i<events.size();i++) {
            var event=events.get(i);var payload=ClaimChangedPayloadV2.parseFrom(event.getPayload());
            assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getEventVersion()).isEqualTo(2);
            assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(event.getAggregateType()).isEqualTo("Claim");
            assertThat(event.getAggregateId()).isEqualTo(claim.claimId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
            assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
            assertThat(payload.getClaimId()).isEqualTo(claim.claimId().toString());assertThat(payload.getSubjectConceptId()).isEqualTo(subject.toString());
            assertThat(payload.getPredicateConceptId()).isEqualTo((i==0?predicate:nextPredicate).toString());assertThat(payload.getObjectConceptId()).isEqualTo((i==0?object:nextObject).toString());
            assertThat(payload.getObjectType()).isEqualTo("CONCEPT");assertThat(payload.getVisibility()).isEqualTo("PRIVATE");assertThat(payload.getVerificationState()).isEqualTo("DRAFT");
            assertThat(payload.getState()).isEqualTo(i==2?"RETIRED":"ACTIVE");assertThat(payload.getVersion()).isEqualTo(i+1L);
            assertThat(payload.getRevisionId()).isEqualTo(history.get(2-i).revisionId().toString());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='claim-v2' AND schema_group='knowledge' AND schema_artifact='claim-events-v2' AND schema_version='1'",Long.class,claim.claimId())).isEqualTo(3);
    }

    @Test void duplicateActiveTriplesConflictWithoutHistoryOrEventsAndRetiredTripleGetsNewId() {
        var first=create();status(409,()->create());UUID different=concept("Different object");
        var second=store.create(space,subject,actor,predicate,ref(different),trace);
        status(409,()->store.change(space,subject,second.claimId(),actor,1,predicate,ref(object),false,trace));
        assertThat(store.get(space,subject,second.claimId()).object()).isEqualTo(ref(different));assertThat(eventCount(second.claimId())).isEqualTo(1);
        assertThat(store.history(space,subject,second.claimId(),Long.MAX_VALUE,10)).hasSize(1);assertThat(spaceEvents()).isEqualTo(2);
        status(412,()->store.change(space,subject,second.claimId(),actor,2,predicate,ref(object),false,trace));assertThat(spaceEvents()).isEqualTo(2);
        store.change(space,subject,first.claimId(),actor,1,null,null,true,trace);var replacement=create();
        assertThat(replacement.claimId()).isNotEqualTo(first.claimId());assertThat(replacement.version()).isEqualTo(1);
        assertThat(store.history(space,subject,first.claimId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(eventCount(first.claimId())).isEqualTo(2);
        assertThat(store.create(space,object,actor,predicate,ref(subject),trace).subjectConceptId()).isEqualTo(object);
    }

    @Test void concurrentEditorsAndDuplicateCreatorsProduceOneWinnerWithoutPartialEvents() throws Exception {
        var claim=create();UUID changedObject=concept("Concurrent winner");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)tasks.add(workers.submit(()->{start.await();try{store.change(space,subject,claim.claimId(),actor,1,predicate,ref(changedObject),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(200,412);assertThat(Collections.frequency(codes,200)).isEqualTo(1);
            var createStart=new CountDownLatch(1);tasks.clear();
            for(int i=0;i<4;i++)tasks.add(workers.submit(()->{createStart.await();try{create();return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            createStart.countDown();codes.clear();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(201,409);assertThat(Collections.frequency(codes,201)).isEqualTo(1);
        }
        assertThat(eventCount(claim.claimId())).isEqualTo(2);assertThat(store.history(space,subject,claim.claimId(),Long.MAX_VALUE,10)).hasSize(2);
        assertThat(store.list(space,subject,null,20).items()).hasSize(2);assertThat(spaceEvents()).isEqualTo(3);
    }

    @Test void reverseTriplesLockConceptUnionInOneOrderAndRepeatedPositionsLockOnce() throws Exception {
        var first=store.create(space,subject,actor,predicate,ref(object),trace);
        var reverse=store.create(space,predicate,actor,subject,ref(object),trace);
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Future<ClaimStore.Claim> a=workers.submit(()->{start.await();return store.change(space,subject,first.claimId(),actor,1,object,ref(predicate),false,trace);});
            Future<ClaimStore.Claim> b=workers.submit(()->{start.await();return store.change(space,predicate,reverse.claimId(),actor,1,object,ref(subject),false,trace);});
            start.countDown();assertThat(a.get(20,TimeUnit.SECONDS).version()).isEqualTo(2);assertThat(b.get(20,TimeUnit.SECONDS).version()).isEqualTo(2);
        }
        assertThat(eventCount(first.claimId())).isEqualTo(2);assertThat(eventCount(reverse.claimId())).isEqualTo(2);
        var self=store.create(space,subject,actor,subject,ref(subject),trace);
        assertThat(store.change(space,subject,self.claimId(),actor,1,subject,ref(subject),false,trace).version()).isEqualTo(2);
        assertThat(store.change(space,subject,self.claimId(),actor,2,null,null,true,trace).version()).isEqualTo(3);assertThat(eventCount(self.claimId())).isEqualTo(3);
    }

    @Test void guessedFutureEtagCannotUseAnEarlierReferenceSnapshotAfterWaitingForConceptLock() throws Exception {
        var claim=store.create(space,subject,actor,subject,ref(subject),trace);
        UUID proposed=concept("Queued future version object"),winner=concept("Winning changed lock-set object");
        String application="claim-future-etag-"+ids.next();
        var queuedSource=new PGSimpleDataSource();queuedSource.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        queuedSource.setUser("brain_knowledge");queuedSource.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));queuedSource.setApplicationName(application);
        var queuedStore=new ClaimStore(queuedSource,Clock.systemUTC());
        try(var holder=source.getConnection()) {
            holder.setAutoCommit(false);
            try(var statement=holder.prepareStatement("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? FOR UPDATE")) {
                statement.setObject(1,space);statement.setObject(2,subject);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
            }
            try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Integer> queued=workers.submit(()->{try{queuedStore.change(space,subject,claim.claimId(),actor,2,subject,ref(proposed),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
                try {
                    boolean waiting=false;long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
                    while(!queued.isDone() && System.nanoTime()<deadline) {
                        waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND state='active'",Long.class,application)>0;
                        if(waiting)break;Thread.sleep(20);
                    }
                    // Early rejection is also safe; otherwise prove the initial read has reached the held Concept lock.
                    if(queued.isDone())assertThat(queued.get(1,TimeUnit.SECONDS)).isEqualTo(412);else assertThat(waiting).as("queued change waited on the held Concept lock").isTrue();
                    var holderStore=new ClaimStore(new SingleConnectionDataSource(holder,true),Clock.systemUTC());
                    assertThat(holderStore.change(space,subject,claim.claimId(),actor,1,subject,ref(winner),false,trace).version()).isEqualTo(2);
                    assertThat(queued.get(20,TimeUnit.SECONDS)).isEqualTo(412);
                } finally {holder.rollback();}
            }
        }
        var current=store.get(space,subject,claim.claimId());assertThat(current.version()).isEqualTo(2);assertThat(current.object()).isEqualTo(ref(winner));
        assertThat(store.history(space,subject,claim.claimId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(eventCount(claim.claimId())).isEqualTo(2);
    }

    @Test void outboxFailureRollsBackCreateUpdateRetireAndImmutableRevisionAsOneTransaction() {
        var claim=create();UUID changed=concept("Rollback candidate");
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_claim_trace CHECK(trace_id <> 'dddddddddddddddddddddddddddddddd') NOT VALID");
        try {
            assertThatThrownBy(()->store.change(space,subject,claim.claimId(),actor,1,predicate,ref(changed),false,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,subject,claim.claimId()).object()).isEqualTo(ref(object));assertThat(store.get(space,subject,claim.claimId()).version()).isEqualTo(1);
            assertThat(store.history(space,subject,claim.claimId(),Long.MAX_VALUE,10)).hasSize(1);assertThat(eventCount(claim.claimId())).isEqualTo(1);
            assertThatThrownBy(()->store.create(space,subject,actor,predicate,ref(changed),"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.list(space,subject,null,20).items()).hasSize(1);
            assertThatThrownBy(()->store.change(space,subject,claim.claimId(),actor,1,null,null,true,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,subject,claim.claimId()).state()).isEqualTo("ACTIVE");assertThat(spaceEvents()).isEqualTo(1);
        } finally {jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_claim_trace");}
        assertThat(store.change(space,subject,claim.claimId(),actor,1,predicate,ref(changed),false,trace).version()).isEqualTo(2);
    }

    @Test void spaceSubjectAndPrivateReferenceScopingNeverExposeForeignClaims() {
        var claim=create();UUID foreignSpace=ids.next();
        UUID secret=concepts.create(foreignSpace,actor,"Foreign secret","",trace).conceptId();
        var foreign=store.create(foreignSpace,secret,actor,secret,ref(secret),trace);
        status(404,()->store.create(space,secret,actor,predicate,ref(object),trace));
        status(404,()->store.create(space,subject,actor,secret,ref(object),trace));status(404,()->store.create(space,subject,actor,predicate,ref(secret),trace));
        status(404,()->store.get(foreignSpace,subject,claim.claimId()));status(404,()->store.get(space,predicate,claim.claimId()));
        status(404,()->store.history(foreignSpace,subject,claim.claimId(),Long.MAX_VALUE,10));status(404,()->store.history(space,predicate,claim.claimId(),Long.MAX_VALUE,10));
        status(404,()->store.list(space,secret,null,20));status(404,()->store.get(space,secret,foreign.claimId()));
        status(404,()->store.change(space,subject,claim.claimId(),actor,1,secret,ref(object),false,trace));
        status(404,()->store.change(space,subject,claim.claimId(),actor,1,predicate,ref(secret),false,trace));
        UUID publicId=ids.next();concepts.createWithId(space,publicId,actor,"Public in same service","",trace,"PUBLIC");
        status(404,()->store.create(space,publicId,actor,predicate,ref(object),trace));status(404,()->store.create(space,subject,actor,publicId,ref(object),trace));
        status(404,()->store.create(space,subject,actor,predicate,ref(publicId),trace));status(404,()->store.list(space,publicId,null,20));
        assertThat(store.list(space,subject,null,20).items()).extracting(ClaimStore.Claim::claimId).containsExactly(claim.claimId());
        assertThat(store.get(foreignSpace,secret,foreign.claimId()).object()).isEqualTo(ref(secret));assertThat(eventCount(claim.claimId())).isEqualTo(1);
    }

    @Test void stableKeysetPaginationIncludesOnlyActiveSameSubjectTriples() {
        var first=create();var second=store.create(space,subject,actor,predicate,ref(concept("Second object")),trace);
        var third=store.create(space,subject,actor,predicate,ref(concept("Third object")),trace);
        store.create(space,predicate,actor,subject,ref(object),trace);
        var page=store.list(space,subject,null,1);assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isNotNull();
        var remaining=store.list(space,subject,page.nextAfter(),100);assertThat(remaining.items()).hasSize(2);assertThat(remaining.nextAfter()).isNull();
        var read=new HashSet<UUID>();page.items().forEach(c->read.add(c.claimId()));remaining.items().forEach(c->read.add(c.claimId()));
        assertThat(read).containsExactlyInAnyOrder(first.claimId(),second.claimId(),third.claimId());
        store.change(space,subject,second.claimId(),actor,1,null,null,true,trace);
        assertThat(store.list(space,subject,null,100).items()).extracting(ClaimStore.Claim::claimId).containsExactly(first.claimId(),third.claimId());
        status(404,()->store.get(space,subject,second.claimId()));assertThat(store.history(space,subject,second.claimId(),Long.MAX_VALUE,10)).hasSize(2);
    }

    @Test void retiringAnyReferenceHidesClaimAndBlocksAllWritesWhileRetainingCanonicalAudit() {
        for(int position=0;position<3;position++) {
            UUID s=concept("Retirement subject "+position),p=concept("Retirement predicate "+position),o=concept("Retirement object "+position);
            var claim=store.create(space,s,actor,p,ref(o),trace);var initial=store.history(space,s,claim.claimId(),Long.MAX_VALUE,10);
            UUID retired=List.of(s,p,o).get(position);concepts.change(space,retired,actor,1,null,null,true,trace);
            status(404,()->store.get(space,s,claim.claimId()));
            if(position==0)status(404,()->store.list(space,s,null,20));else assertThat(store.list(space,s,null,20).items()).isEmpty();
            status(404,()->store.change(space,s,claim.claimId(),actor,1,subject,ref(object),false,trace));
            status(404,()->store.change(space,s,claim.claimId(),actor,1,null,null,true,trace));
            status(404,()->store.create(space,s,actor,p,ref(o),trace));
            assertThat(store.history(space,s,claim.claimId(),Long.MAX_VALUE,10)).isEqualTo(initial);assertThat(eventCount(claim.claimId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM claim WHERE claim_id=?",String.class,claim.claimId())).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT version FROM claim WHERE claim_id=?",Long.class,claim.claimId())).isEqualTo(1);
        }
        var active=create();UUID proposed=concept("Retired proposed reference");concepts.change(space,proposed,actor,1,null,null,true,trace);
        status(404,()->store.change(space,subject,active.claimId(),actor,1,proposed,ref(object),false,trace));
        status(404,()->store.change(space,subject,active.claimId(),actor,1,predicate,ref(proposed),false,trace));assertThat(eventCount(active.claimId())).isEqualTo(1);
        for(UUID id:List.of(subject,predicate,object))concepts.change(space,id,actor,1,null,null,true,trace);
        assertThat(store.history(space,subject,active.claimId(),Long.MAX_VALUE,10)).hasSize(1);
    }

    @Test void referenceRetirementRacingWithCreateAndUpdateCannotAdmitLaterWrites() throws Exception {
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int position=0;position<3;position++) {
                UUID s=concept("Create race subject "+position),p=concept("Create race predicate "+position),o=concept("Create race object "+position),retired=List.of(s,p,o).get(position);
                var start=new CountDownLatch(1);
                Future<Integer> creation=workers.submit(()->{start.await();try{store.create(space,s,actor,p,ref(o),trace);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
                Future<Integer> retirement=workers.submit(()->{start.await();concepts.change(space,retired,actor,1,null,null,true,trace);return 204;});
                start.countDown();assertThat(creation.get(20,TimeUnit.SECONDS)).isIn(201,404);assertThat(retirement.get(20,TimeUnit.SECONDS)).isEqualTo(204);
                status(404,()->store.create(space,s,actor,p,ref(o),trace));
                var records=jdbc.query("SELECT claim_id FROM claim WHERE space_id=? AND subject_concept_id=?",(rs,n)->rs.getObject(1,UUID.class),space,s);
                assertThat(records).hasSizeLessThanOrEqualTo(1);for(UUID id:records){status(404,()->store.get(space,s,id));assertThat(eventCount(id)).isEqualTo(1);}
            }
            var claim=create();UUID changed=concept("Update race proposed object");var start=new CountDownLatch(1);
            Future<Integer> update=workers.submit(()->{start.await();try{store.change(space,subject,claim.claimId(),actor,1,predicate,ref(changed),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            Future<Integer> retire=workers.submit(()->{start.await();concepts.change(space,changed,actor,1,null,null,true,trace);return 204;});
            start.countDown();int code=update.get(20,TimeUnit.SECONDS);assertThat(code).isIn(200,404);assertThat(retire.get(20,TimeUnit.SECONDS)).isEqualTo(204);
            status(404,()->store.change(space,subject,claim.claimId(),actor,code==200?2:1,predicate,ref(changed),false,trace));
            assertThat(store.history(space,subject,claim.claimId(),Long.MAX_VALUE,10)).hasSize(code==200?2:1);assertThat(eventCount(claim.claimId())).isEqualTo(code==200?2:1);
            if(code==200)status(404,()->store.get(space,subject,claim.claimId()));else assertThat(store.get(space,subject,claim.claimId()).object()).isEqualTo(ref(object));
        }
    }

    @Test void databaseConstraintsEnforceTenantReferencesPrivateDraftTypedObjectAndUniqueRevisions() {
        var claim=create();UUID foreignSpace=ids.next(),foreign=concepts.create(foreignSpace,actor,"Foreign constraint","",trace).conceptId();
        assertThatThrownBy(()->jdbc.update("UPDATE claim SET space_id=? WHERE claim_id=?",foreignSpace,claim.claimId())).isInstanceOf(DataAccessException.class);
        for(String column:List.of("subject_concept_id","predicate_concept_id","object_concept_id"))
            assertThatThrownBy(()->jdbc.update("UPDATE claim SET "+column+"=? WHERE claim_id=?",foreign,claim.claimId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim SET visibility='PUBLIC' WHERE claim_id=?",claim.claimId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim SET verification_state='EXPERT_VERIFIED' WHERE claim_id=?",claim.claimId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim SET object_type='STRING' WHERE claim_id=?",claim.claimId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim SET version=0 WHERE claim_id=?",claim.claimId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO claim_revision(revision_id,claim_id,subject_concept_id,predicate_concept_id,object_concept_id,object_type,version,state,verification_state,actor_id,occurred_at) VALUES (?,?,?,?,?,'CONCEPT',1,'ACTIVE','DRAFT',?,now())",ids.next(),claim.claimId(),subject,predicate,object,actor)).isInstanceOf(DataAccessException.class);
        assertThat(store.get(space,subject,claim.claimId()).object()).isEqualTo(ref(object));assertThat(eventCount(claim.claimId())).isEqualTo(1);
    }

    @Test void invalidIdsTypedObjectsVersionsAndCursorsCannotCreateFactsOrEvents() {
        status(400,()->store.create(UUID.randomUUID(),subject,actor,predicate,ref(object),trace));status(400,()->store.create(space,UUID.randomUUID(),actor,predicate,ref(object),trace));
        status(400,()->store.create(space,subject,UUID.randomUUID(),predicate,ref(object),trace));status(400,()->store.create(space,subject,actor,UUID.randomUUID(),ref(object),trace));
        for(ClaimStore.ObjectValue value:List.of(new ClaimStore.ObjectValue("STRING",object),new ClaimStore.ObjectValue("CONCEPT",UUID.randomUUID()),new ClaimStore.ObjectValue("CONCEPT",null),new ClaimStore.ObjectValue(null,object)))
            status(400,()->store.create(space,subject,actor,predicate,value,trace));
        status(400,()->store.create(space,subject,actor,predicate,null,trace));
        status(400,()->store.list(space,subject,UUID.randomUUID(),20));status(400,()->store.list(space,subject,null,0));status(400,()->store.list(space,subject,null,101));
        assertThat(spaceEvents()).isZero();var claim=create();
        status(400,()->store.history(space,subject,claim.claimId(),0,1));status(400,()->store.history(space,subject,claim.claimId(),Long.MAX_VALUE,11));
        status(400,()->store.change(space,subject,claim.claimId(),actor,0,predicate,ref(object),false,trace));assertThat(eventCount(claim.claimId())).isEqualTo(1);
    }
}
