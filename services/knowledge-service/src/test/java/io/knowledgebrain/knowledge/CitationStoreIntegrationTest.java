package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.citation.v2.*;
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
class CitationStoreIntegrationTest {
    PGSimpleDataSource data;
    JdbcTemplate jdbc;
    CitationStore store;
    ConceptStore concepts;
    ClaimStore claims;
    SourceStore sources;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    String trace="c".repeat(32);
    UUID space,actor,subject,predicate,object;
    ClaimStore.Claim claim;
    SourceStore.Source source;

    @BeforeEach void prepare() {
        data=dataSource(null);Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(data);store=new CitationStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());
        claims=new ClaimStore(data,Clock.systemUTC());sources=new SourceStore(data,Clock.systemUTC());space=ids.next();actor=ids.next();
        subject=concept("Subject");predicate=concept("Predicate");object=concept("Object");
        claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",object),trace);source=source("Original source");
    }
    PGSimpleDataSource dataSource(String application) {
        var result=new PGSimpleDataSource();result.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));result.setUser("brain_knowledge");
        result.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));if(application!=null)result.setApplicationName(application);return result;
    }
    UUID concept(String title) {return concepts.create(space,actor,title,"",trace).conceptId();}
    SourceStore.Source source(String title) {return sources.create(space,actor,title,"https://example.org/original#page-1","  Original\ncitation  ","  Manual rights note  ",trace);}
    CitationStore.Reference ref(SourceStore.Source s,long claimVersion) {return new CitationStore.Reference(s.sourceId(),s.version(),claimVersion);}
    CitationStore.Citation create() {return store.create(space,subject,claim.claimId(),actor,ref(source,1),trace);}
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }
    long eventCount(UUID id) {return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='citation-v2'",Long.class,id);}
    long spaceEvents() {return jdbc.queryForObject("SELECT count(*) FROM outbox_event o JOIN claim_citation c ON c.citation_id=o.aggregateid WHERE c.space_id=? AND o.aggregatetype='citation-v2'",Long.class,space);}
    UUID sourceRevision(SourceStore.Source s) {return sources.history(space,s.sourceId(),Long.MAX_VALUE,1).getFirst().revisionId();}
    UUID claimRevision(ClaimStore.Claim c) {return claims.history(space,c.subjectConceptId(),c.claimId(),Long.MAX_VALUE,1).getFirst().revisionId();}
    List<EventEnvelopeV2> events(UUID id) {
        return jdbc.query("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='citation-v2' ORDER BY occurred_at,id",
            (rs,n)->{try{return EventEnvelopeV2.parseFrom(rs.getBytes(1));}catch(Exception e){throw new IllegalStateException(e);}},id);
    }

    @Test void pinnedSourceAndRelationSnapshotsNeverChangeUnderSameCitationVersionAndParentsStayIndependent() throws Exception {
        var parentConcepts=List.of(concepts.get(space,subject),concepts.get(space,predicate),concepts.get(space,object));
        var aliases=new AliasStore(data,Clock.systemUTC());var alias=aliases.create(space,subject,actor,"Independent alias",trace);
        UUID initialSourceRevision=sourceRevision(source),initialClaimRevision=claimRevision(claim);var entry=create();
        assertThat(entry.citationId().version()).isEqualTo(7);assertThat(entry.version()).isEqualTo(1);assertThat(entry.state()).isEqualTo("ACTIVE");assertThat(entry.visibility()).isEqualTo("PRIVATE");
        assertThat(entry.source()).isEqualTo(new CitationStore.SourceSnapshot(source.sourceId(),initialSourceRevision,1,source.title(),source.url(),source.citation(),source.licenseNote()));
        assertThat(entry.claim()).isEqualTo(new CitationStore.ClaimSnapshot(initialClaimRevision,1,subject,predicate,claim.object()));
        var initial=store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10).getFirst();assertThat(initial.revisionId().version()).isEqualTo(7);
        var latestSource=sources.change(space,source.sourceId(),actor,1,"Edited title",null,"Book, 2026","Edited rights note",false,trace);
        UUID nextObject=concept("Changed object");var latestClaim=claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",nextObject),false,trace);
        store=new CitationStore(data,Clock.systemUTC());assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);
        assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10)).containsExactly(initial);
        var changed=store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(latestSource,2),false,trace);
        assertThat(changed.citationId()).isEqualTo(entry.citationId());assertThat(changed.createdAt()).isEqualTo(entry.createdAt());assertThat(changed.version()).isEqualTo(2);
        assertThat(changed.source().revisionId()).isEqualTo(sourceRevision(latestSource));assertThat(changed.claim().revisionId()).isEqualTo(claimRevision(latestClaim));
        assertThat(changed.source().url()).isNull();assertThat(changed.source().title()).isEqualTo("Edited title");assertThat(changed.claim().object().conceptId()).isEqualTo(nextObject);
        assertThat(claims.get(space,subject,claim.claimId())).isEqualTo(latestClaim);assertThat(sources.get(space,source.sourceId())).isEqualTo(latestSource);
        assertThat(claims.get(space,subject,claim.claimId()).verificationState()).isEqualTo("DRAFT");
        assertThat(List.of(concepts.get(space,subject),concepts.get(space,predicate),concepts.get(space,object))).isEqualTo(parentConcepts);
        assertThat(aliases.get(space,subject,alias.aliasId())).isEqualTo(alias);
        assertThat(claims.history(space,subject,claim.claimId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(sources.history(space,source.sourceId(),Long.MAX_VALUE,10)).hasSize(2);
        sources.change(space,source.sourceId(),actor,2,null,null,null,null,true,trace);
        assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(changed);
        assertThat(store.list(space,subject,claim.claimId(),null,20).items()).containsExactly(changed);
        status(404,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,2,new CitationStore.Reference(source.sourceId(),3,2),false,trace));
        var retired=store.change(space,subject,claim.claimId(),entry.citationId(),actor,2,null,true,trace);assertThat(retired.version()).isEqualTo(3);
        assertThat(retired.source()).isEqualTo(changed.source());assertThat(retired.claim()).isEqualTo(changed.claim());
        status(404,()->store.get(space,subject,claim.claimId(),entry.citationId()));assertThat(store.list(space,subject,claim.claimId(),null,20).items()).isEmpty();
        var history=store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10);
        assertThat(history).extracting(CitationStore.Revision::version).containsExactly(3L,2L,1L);assertThat(history.getLast()).isEqualTo(initial);
        assertThat(history.getFirst().state()).isEqualTo("RETIRED");assertThat(history.getFirst().actorId()).isEqualTo(actor);
        var events=events(entry.citationId());assertThat(events).extracting(EventEnvelopeV2::getEventType).containsExactly("ClaimCitationCreated","ClaimCitationUpdated","ClaimCitationRetired");
        for(int i=0;i<events.size();i++) {
            var event=events.get(i);var payload=ClaimCitationChangedPayloadV2.parseFrom(event.getPayload());var revision=history.get(2-i);
            assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getEventVersion()).isEqualTo(2);
            assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(event.getAggregateType()).isEqualTo("ClaimCitation");
            assertThat(event.getAggregateId()).isEqualTo(entry.citationId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());assertThat(event.getActorId()).isEqualTo(actor.toString());
            assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
            assertThat(payload.getCitationId()).isEqualTo(entry.citationId().toString());assertThat(payload.getClaimId()).isEqualTo(claim.claimId().toString());assertThat(payload.getSubjectConceptId()).isEqualTo(subject.toString());
            assertThat(payload.getClaimRevisionId()).isEqualTo(revision.claim().revisionId().toString());assertThat(payload.getClaimVersion()).isEqualTo(revision.claim().version());
            assertThat(payload.getPredicateConceptId()).isEqualTo(revision.claim().predicateConceptId().toString());assertThat(payload.getObjectType()).isEqualTo("CONCEPT");assertThat(payload.getObjectConceptId()).isEqualTo(revision.claim().object().conceptId().toString());
            assertThat(payload.getSourceId()).isEqualTo(source.sourceId().toString());assertThat(payload.getSourceRevisionId()).isEqualTo(revision.source().revisionId().toString());assertThat(payload.getSourceVersion()).isEqualTo(revision.source().version());
            assertThat(payload.getTitle()).isEqualTo(revision.source().title());assertThat(payload.getUrl()).isEqualTo(revision.source().url()==null?"":revision.source().url());
            assertThat(payload.getCitation()).isEqualTo(revision.source().citation());assertThat(payload.getLicenseNote()).isEqualTo(revision.source().licenseNote());
            assertThat(payload.getRevisionId()).isEqualTo(revision.revisionId().toString());assertThat(payload.getVersion()).isEqualTo(i+1L);assertThat(payload.getState()).isEqualTo(i==2?"RETIRED":"ACTIVE");assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='citation-v2' AND schema_group='knowledge' AND schema_artifact='citation-events-v2' AND schema_version='1'",Long.class,entry.citationId())).isEqualTo(3);
    }

    @Test void staleClaimSourceOrCitationVersionsNeverWriteOrRepinAndParentChangesAreExplicit() {
        var entry=create();var latest=sources.change(space,source.sourceId(),actor,1,"Latest",null,"Latest citation","",false,trace);
        status(412,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(source,1),false,trace));
        status(412,()->store.create(space,subject,claim.claimId(),actor,ref(source,1),trace));
        claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",concept("New object")),false,trace);
        status(412,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(latest,1),false,trace));
        var different=source("Different");status(412,()->store.create(space,subject,claim.claimId(),actor,ref(different,1),trace));
        assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);assertThat(spaceEvents()).isEqualTo(1);
        var changed=store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(latest,2),false,trace);
        status(412,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(different,2),false,trace));
        status(412,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,null,true,trace));
        assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(changed);assertThat(eventCount(entry.citationId())).isEqualTo(2);
    }

    @Test void oneActiveCitationPerSourceAndClaimRetireRecreateGetsNewImmutableIdentity() {
        var first=create();status(409,()->create());var secondSource=source("Second");
        var second=store.create(space,subject,claim.claimId(),actor,ref(secondSource,1),trace);
        status(409,()->store.change(space,subject,claim.claimId(),second.citationId(),actor,1,ref(source,1),false,trace));
        assertThat(store.get(space,subject,claim.claimId(),second.citationId()).source().sourceId()).isEqualTo(secondSource.sourceId());assertThat(eventCount(second.citationId())).isEqualTo(1);
        store.change(space,subject,claim.claimId(),first.citationId(),actor,1,null,true,trace);var replacement=create();
        assertThat(replacement.citationId()).isNotEqualTo(first.citationId());assertThat(replacement.version()).isEqualTo(1);assertThat(store.history(space,subject,claim.claimId(),first.citationId(),Long.MAX_VALUE,10)).hasSize(2);
        var otherClaim=claims.create(space,object,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",subject),trace);
        assertThat(store.create(space,object,otherClaim.claimId(),actor,ref(source,1),trace).claimId()).isEqualTo(otherClaim.claimId());assertThat(spaceEvents()).isEqualTo(5);
    }

    @Test void concurrentEditorsAndDuplicateCreatorsEachHaveOneWinnerWithoutLostAudit() throws Exception {
        var entry=create();var target=source("Concurrent target");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++)tasks.add(workers.submit(()->{start.await();try{store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(target,1),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(200,412);assertThat(Collections.frequency(codes,200)).isEqualTo(1);
            var createStart=new CountDownLatch(1);tasks.clear();
            for(int i=0;i<4;i++)tasks.add(workers.submit(()->{createStart.await();try{create();return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            createStart.countDown();codes.clear();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(201,409);assertThat(Collections.frequency(codes,201)).isEqualTo(1);
        }
        assertThat(eventCount(entry.citationId())).isEqualTo(2);assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10)).hasSize(2);
        assertThat(store.list(space,subject,claim.claimId(),null,100).items()).hasSize(2);assertThat(spaceEvents()).isEqualTo(3);
    }

    @Test void outboxFailureRollsBackCitationRevisionAndAllParentStateAtomically() {
        var entry=create();var target=source("Rollback candidate");
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_citation_trace CHECK(trace_id <> 'dddddddddddddddddddddddddddddddd') NOT VALID");
        try {
            assertThatThrownBy(()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(target,1),false,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);
            assertThatThrownBy(()->store.create(space,subject,claim.claimId(),actor,ref(target,1),"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,null,true,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.list(space,subject,claim.claimId(),null,100).items()).containsExactly(entry);assertThat(spaceEvents()).isEqualTo(1);
            assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10)).hasSize(1);
            assertThat(claims.get(space,subject,claim.claimId())).isEqualTo(claim);assertThat(sources.get(space,source.sourceId())).isEqualTo(source);
        } finally {jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_citation_trace");}
        assertThat(store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(target,1),false,trace).version()).isEqualTo(2);
    }

    @Test void exactSpaceSubjectClaimAndPrivateSourceIdentitiesNeverExposeOtherCitations() {
        var entry=create();UUID foreignSpace=ids.next();var secret=sources.create(foreignSpace,actor,"Foreign secret",null,"Private citation","",trace);
        var foreignConcept=concepts.create(foreignSpace,actor,"Foreign Concept","",trace);var foreignClaim=claims.create(foreignSpace,foreignConcept.conceptId(),actor,foreignConcept.conceptId(),new ClaimStore.ObjectValue("CONCEPT",foreignConcept.conceptId()),trace);
        var foreignEntry=store.create(foreignSpace,foreignConcept.conceptId(),foreignClaim.claimId(),actor,ref(secret,1),trace);
        status(404,()->store.create(space,subject,claim.claimId(),actor,ref(secret,1),trace));
        status(404,()->store.get(foreignSpace,subject,claim.claimId(),entry.citationId()));status(404,()->store.get(space,predicate,claim.claimId(),entry.citationId()));
        status(404,()->store.get(space,subject,foreignClaim.claimId(),entry.citationId()));status(404,()->store.get(space,subject,claim.claimId(),foreignEntry.citationId()));
        status(404,()->store.history(space,predicate,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10));status(404,()->store.history(foreignSpace,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10));
        status(404,()->store.list(space,subject,foreignClaim.claimId(),null,20));status(404,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(secret,1),false,trace));
        UUID publicConcept=ids.next();concepts.createWithId(space,publicConcept,actor,"Public","",trace,"PUBLIC");
        status(404,()->store.create(space,publicConcept,claim.claimId(),actor,ref(source,1),trace));
        assertThat(store.get(foreignSpace,foreignConcept.conceptId(),foreignClaim.claimId(),foreignEntry.citationId())).isEqualTo(foreignEntry);assertThat(spaceEvents()).isEqualTo(1);
    }

    @Test void stableActiveKeysetsAndDescendingHistoryStayWithinTheirClaim() {
        var first=create();var secondSource=source("Second");var thirdSource=source("Third");
        var second=store.create(space,subject,claim.claimId(),actor,ref(secondSource,1),trace);var third=store.create(space,subject,claim.claimId(),actor,ref(thirdSource,1),trace);
        var otherClaim=claims.create(space,object,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",subject),trace);store.create(space,object,otherClaim.claimId(),actor,ref(source,1),trace);
        var page=store.list(space,subject,claim.claimId(),null,1);assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isNotNull();
        var tail=store.list(space,subject,claim.claimId(),page.nextAfter(),100);assertThat(tail.items()).hasSize(2);assertThat(tail.nextAfter()).isNull();
        var read=new HashSet<UUID>();page.items().forEach(c->read.add(c.citationId()));tail.items().forEach(c->read.add(c.citationId()));assertThat(read).containsExactlyInAnyOrder(first.citationId(),second.citationId(),third.citationId());
        store.change(space,subject,claim.claimId(),second.citationId(),actor,1,null,true,trace);
        assertThat(store.list(space,subject,claim.claimId(),null,100).items()).extracting(CitationStore.Citation::citationId).containsExactly(first.citationId(),third.citationId());
        long version=1;for(int i=0;i<7;i++)version=store.change(space,subject,claim.claimId(),first.citationId(),actor,version,ref(source,1),false,trace).version();
        var history=store.history(space,subject,claim.claimId(),first.citationId(),Long.MAX_VALUE,5);assertThat(history).extracting(CitationStore.Revision::version).containsExactly(8L,7L,6L,5L,4L);
        assertThat(store.history(space,subject,claim.claimId(),first.citationId(),4,5)).extracting(CitationStore.Revision::version).containsExactly(3L,2L,1L);
    }

    @Test void claimOrAnyReferencedConceptRetirementBlocksCitationCatalogAndMutationsButKeepsKnownHistory() {
        for(int position=0;position<4;position++) {
            UUID s=concept("Retirement subject "+position),p=concept("Retirement predicate "+position),o=concept("Retirement object "+position);
            var parent=claims.create(space,s,actor,p,new ClaimStore.ObjectValue("CONCEPT",o),trace);
            var entry=store.create(space,s,parent.claimId(),actor,ref(source,1),trace);var audit=store.history(space,s,parent.claimId(),entry.citationId(),Long.MAX_VALUE,10);
            if(position==3)claims.change(space,s,parent.claimId(),actor,1,null,null,true,trace);
            else concepts.change(space,List.of(s,p,o).get(position),actor,1,null,null,true,trace);
            status(404,()->store.get(space,s,parent.claimId(),entry.citationId()));status(404,()->store.list(space,s,parent.claimId(),null,20));
            status(404,()->store.create(space,s,parent.claimId(),actor,ref(source,1),trace));
            status(404,()->store.change(space,s,parent.claimId(),entry.citationId(),actor,1,ref(source,1),false,trace));status(404,()->store.change(space,s,parent.claimId(),entry.citationId(),actor,1,null,true,trace));
            assertThat(store.history(space,s,parent.claimId(),entry.citationId(),Long.MAX_VALUE,10)).isEqualTo(audit);assertThat(eventCount(entry.citationId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT version FROM claim_citation WHERE citation_id=?",Long.class,entry.citationId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT state FROM claim_citation WHERE citation_id=?",String.class,entry.citationId())).isEqualTo("ACTIVE");
        }
    }

    void waitForLockOrEarlyFailure(Future<Integer> queued,String application) throws Exception {
        boolean waiting=false;long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(!queued.isDone() && System.nanoTime()<deadline) {
            waiting=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock' AND state='active'",Long.class,application)>0;
            if(waiting)break;Thread.sleep(20);
        }
        if(queued.isDone())assertThat(queued.get(1,TimeUnit.SECONDS)).isEqualTo(412);
        else assertThat(waiting).as("queued citation operation reached held reference lock").isTrue();
    }
    void lock(java.sql.Connection holder,String table,String idColumn,UUID id) throws Exception {
        try(var statement=holder.prepareStatement("SELECT "+idColumn+" FROM "+table+" WHERE space_id=? AND "+idColumn+"=? FOR UPDATE")) {
            statement.setObject(1,space);statement.setObject(2,id);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}
        }
    }

    @Test void guessedFutureClaimVersionCannotCreateWithAnEarlierConceptLockSetAfterWaiting() throws Exception {
        var self=claims.create(space,subject,actor,subject,new ClaimStore.ObjectValue("CONCEPT",subject),trace);UUID changed=concept("Winning Claim reference");
        String application="citation-future-claim-"+ids.next();var queuedStore=new CitationStore(dataSource(application),Clock.systemUTC());
        try(var holder=data.getConnection();var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);lock(holder,"concept","concept_id",subject);
            Future<Integer> queued=workers.submit(()->{try{queuedStore.create(space,subject,self.claimId(),actor,ref(source,2),trace);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            try {
                waitForLockOrEarlyFailure(queued,application);
                var holderClaims=new ClaimStore(new SingleConnectionDataSource(holder,true),Clock.systemUTC());
                assertThat(holderClaims.change(space,subject,self.claimId(),actor,1,subject,new ClaimStore.ObjectValue("CONCEPT",changed),false,trace).version()).isEqualTo(2);
                assertThat(queued.get(20,TimeUnit.SECONDS)).isEqualTo(412);
            } finally {holder.rollback();}
        }
        assertThat(store.list(space,subject,self.claimId(),null,20).items()).isEmpty();assertThat(spaceEvents()).isZero();
    }

    @Test void guessedFutureCitationVersionCannotUseEarlierPriorSourceLockSetAfterWaiting() throws Exception {
        var entry=create();var winner=source("Winning changed Source lock set");var proposed=source("Queued proposed Source");
        String application="citation-future-cite-"+ids.next();var queuedStore=new CitationStore(dataSource(application),Clock.systemUTC());
        try(var holder=data.getConnection();var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);lock(holder,"concept","concept_id",subject);
            Future<Integer> queued=workers.submit(()->{try{queuedStore.change(space,subject,claim.claimId(),entry.citationId(),actor,2,ref(proposed,1),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            try {
                waitForLockOrEarlyFailure(queued,application);
                var holderStore=new CitationStore(new SingleConnectionDataSource(holder,true),Clock.systemUTC());
                assertThat(holderStore.change(space,subject,claim.claimId(),entry.citationId(),actor,1,ref(winner,1),false,trace).version()).isEqualTo(2);
                assertThat(queued.get(20,TimeUnit.SECONDS)).isEqualTo(412);
            } finally {holder.rollback();}
        }
        var current=store.get(space,subject,claim.claimId(),entry.citationId());assertThat(current.version()).isEqualTo(2);assertThat(current.source().sourceId()).isEqualTo(winner.sourceId());
        assertThat(eventCount(entry.citationId())).isEqualTo(2);assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10)).hasSize(2);
    }

    @Test void sourceRevisionChangingDuringHeldSourceLockRejectsStalePinWithoutCitationFacts() throws Exception {
        String application="citation-source-race-"+ids.next();var queuedStore=new CitationStore(dataSource(application),Clock.systemUTC());
        try(var holder=data.getConnection();var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);lock(holder,"knowledge_source","source_id",source.sourceId());
            Future<Integer> queued=workers.submit(()->{try{queuedStore.create(space,subject,claim.claimId(),actor,ref(source,1),trace);return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            try {
                waitForLockOrEarlyFailure(queued,application);
                var holderSources=new SourceStore(new SingleConnectionDataSource(holder,true),Clock.systemUTC());
                assertThat(holderSources.change(space,source.sourceId(),actor,1,"Committed replacement",null,"New citation","",false,trace).version()).isEqualTo(2);
                assertThat(queued.get(20,TimeUnit.SECONDS)).isEqualTo(412);
            } finally {holder.rollback();}
        }
        assertThat(store.list(space,subject,claim.claimId(),null,20).items()).isEmpty();assertThat(spaceEvents()).isZero();
    }

    @Test void databaseCompositeKeysPreventForeignTenantMismatchedRevisionIdentityAndDuplicateActiveCitation() {
        var entry=create();var otherSource=source("Other revision owner");UUID otherSourceRevision=sourceRevision(otherSource);
        var otherClaim=claims.create(space,object,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",subject),trace);UUID otherClaimRevision=claimRevision(otherClaim);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET space_id=? WHERE citation_id=?",ids.next(),entry.citationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET subject_concept_id=? WHERE citation_id=?",object,entry.citationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET source_revision_id=? WHERE citation_id=?",otherSourceRevision,entry.citationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET claim_revision_id=? WHERE citation_id=?",otherClaimRevision,entry.citationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET visibility='PUBLIC' WHERE citation_id=?",entry.citationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET state='UNKNOWN' WHERE citation_id=?",entry.citationId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET version=0 WHERE citation_id=?",entry.citationId())).isInstanceOf(DataAccessException.class);
        var otherCitation=store.create(space,subject,claim.claimId(),actor,ref(otherSource,1),trace);
        assertThatThrownBy(()->jdbc.update("UPDATE claim_citation SET source_id=?,source_revision_id=? WHERE citation_id=?",source.sourceId(),entry.source().revisionId(),otherCitation.citationId())).isInstanceOf(DataAccessException.class);
        var revision=store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10).getFirst();
        assertThatThrownBy(()->jdbc.update("UPDATE citation_revision SET source_revision_id=? WHERE revision_id=?",otherSourceRevision,revision.revisionId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE citation_revision SET claim_revision_id=? WHERE revision_id=?",otherClaimRevision,revision.revisionId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO citation_revision(revision_id,citation_id,version,state,claim_id,source_id,claim_revision_id,source_revision_id,actor_id,occurred_at) VALUES (?,?,1,'ACTIVE',?,?,?,?,?,now())",ids.next(),entry.citationId(),claim.claimId(),source.sourceId(),entry.claim().revisionId(),entry.source().revisionId(),actor)).isInstanceOf(DataAccessException.class);
        assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);assertThat(eventCount(entry.citationId())).isEqualTo(1);
    }

    @Test void invalidIdsReferenceVersionsCursorsTraceAndOverflowNeverWriteFacts() {
        status(400,()->CitationStore.reference(UUID.randomUUID(),1,1));status(400,()->CitationStore.reference(source.sourceId(),0,1));status(400,()->CitationStore.reference(source.sourceId(),1,0));
        status(400,()->store.create(UUID.randomUUID(),subject,claim.claimId(),actor,ref(source,1),trace));status(400,()->store.create(space,subject,claim.claimId(),UUID.randomUUID(),ref(source,1),trace));
        status(400,()->store.create(space,subject,claim.claimId(),actor,null,trace));
        status(400,()->store.list(space,subject,claim.claimId(),UUID.randomUUID(),20));status(400,()->store.list(space,subject,claim.claimId(),null,0));status(400,()->store.list(space,subject,claim.claimId(),null,101));
        for(String invalid:List.of("","f","F".repeat(32),"0".repeat(32)))assertThatThrownBy(()->store.create(space,subject,claim.claimId(),actor,ref(source,1),invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(spaceEvents()).isZero();var entry=create();
        status(400,()->store.history(space,subject,claim.claimId(),entry.citationId(),0,1));status(400,()->store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,11));
        status(400,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,0,ref(source,1),false,trace));
        jdbc.update("UPDATE claim_citation SET version=? WHERE citation_id=?",Long.MAX_VALUE,entry.citationId());
        status(409,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,Long.MAX_VALUE,ref(source,1),false,trace));
        status(409,()->store.change(space,subject,claim.claimId(),entry.citationId(),actor,Long.MAX_VALUE,null,true,trace));
        assertThat(eventCount(entry.citationId())).isEqualTo(1);assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10)).hasSize(1);
    }
}
