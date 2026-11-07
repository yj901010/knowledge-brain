package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.source.v1.*;
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
class SourceStoreIntegrationTest {
    PGSimpleDataSource source;
    JdbcTemplate jdbc;
    SourceStore store;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    String trace="c".repeat(32);
    UUID space,actor;

    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);store=new SourceStore(source,Clock.systemUTC());space=ids.next();actor=ids.next();
    }
    SourceStore.Source create(String title) {return store.create(space,actor,title,"https://example.org/original","Original citation","User license note",trace);}
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }
    long eventCount(UUID id) {return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='source'",Long.class,id);}
    long spaceEvents() {return jdbc.queryForObject("SELECT count(*) FROM outbox_event o JOIN knowledge_source s ON s.source_id=o.aggregateid WHERE s.space_id=? AND o.aggregatetype='source'",Long.class,space);}
    List<EventEnvelopeV1> events(UUID id) {
        return jdbc.query("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='source' ORDER BY occurred_at,id",
            (rs,n)->{try{return EventEnvelopeV1.parseFrom(rs.getBytes(1));}catch(Exception e){throw new IllegalStateException(e);}},id);
    }

    @Test void independentRevisionStreamPreservesMetadataAndOtherKnowledgeAcrossRestartAndRetirement() throws Exception {
        var concepts=new ConceptStore(source,Clock.systemUTC());var aliases=new AliasStore(source,Clock.systemUTC());var claims=new ClaimStore(source,Clock.systemUTC());
        var concept=concepts.create(space,actor,"Independent concept","Original definition",trace);
        var alias=aliases.create(space,concept.conceptId(),actor,"Independent alias",trace);
        var claim=claims.create(space,concept.conceptId(),actor,concept.conceptId(),new ClaimStore.ObjectValue("CONCEPT",concept.conceptId()),trace);
        var first=store.create(space,actor,"  원본 문헌  "," https://example.org/original#page-1 ","  원본\n인용문  ","  배포 권리 미확인  ",trace);
        assertThat(first.sourceId().version()).isEqualTo(7);assertThat(first.spaceId()).isEqualTo(space);assertThat(first.title()).isEqualTo("원본 문헌");
        assertThat(first.url()).isEqualTo("https://example.org/original#page-1");assertThat(first.citation()).isEqualTo("  원본\n인용문  ");
        assertThat(first.licenseNote()).isEqualTo("  배포 권리 미확인  ");assertThat(first.visibility()).isEqualTo("PRIVATE");assertThat(first.state()).isEqualTo("ACTIVE");assertThat(first.version()).isEqualTo(1);
        var initial=store.history(space,first.sourceId(),Long.MAX_VALUE,10).getFirst();assertThat(initial.revisionId().version()).isEqualTo(7);
        var updated=store.change(space,first.sourceId(),actor,1,"Printed reference",null,"Author, Book, 2026","Manual license annotation",false,trace);
        assertThat(updated.sourceId()).isEqualTo(first.sourceId());assertThat(updated.createdAt()).isEqualTo(first.createdAt());assertThat(updated.version()).isEqualTo(2);assertThat(updated.url()).isNull();
        store=new SourceStore(source,Clock.systemUTC());assertThat(store.get(space,first.sourceId())).isEqualTo(updated);
        store.change(space,first.sourceId(),actor,2,null,null,null,null,true,trace);
        status(404,()->store.get(space,first.sourceId()));assertThat(store.list(space,null,20).items()).isEmpty();
        var revisions=store.history(space,first.sourceId(),Long.MAX_VALUE,10);
        assertThat(revisions).extracting(SourceStore.Revision::version).containsExactly(3L,2L,1L);assertThat(revisions.getLast()).isEqualTo(initial);
        assertThat(revisions.getFirst().state()).isEqualTo("RETIRED");assertThat(revisions.getFirst().title()).isEqualTo("Printed reference");assertThat(revisions.getFirst().url()).isNull();
        assertThat(revisions.getFirst().citation()).isEqualTo("Author, Book, 2026");assertThat(revisions.getFirst().licenseNote()).isEqualTo("Manual license annotation");
        assertThat(store.history(space,first.sourceId(),3,1)).extracting(SourceStore.Revision::version).containsExactly(2L);
        assertThat(concepts.get(space,concept.conceptId())).isEqualTo(concept);assertThat(aliases.get(space,concept.conceptId(),alias.aliasId())).isEqualTo(alias);
        assertThat(claims.get(space,concept.conceptId(),claim.claimId())).isEqualTo(claim);
        assertThat(concepts.history(space,concept.conceptId(),Long.MAX_VALUE,10)).hasSize(1);assertThat(aliases.history(space,concept.conceptId(),alias.aliasId(),Long.MAX_VALUE,10)).hasSize(1);
        assertThat(claims.history(space,concept.conceptId(),claim.claimId(),Long.MAX_VALUE,10)).hasSize(1);
        var events=events(first.sourceId());assertThat(events).extracting(EventEnvelopeV1::getEventType).containsExactly("SourceCreated","SourceUpdated","SourceRetired");
        for(int i=0;i<events.size();i++) {
            var event=events.get(i);var payload=SourceChangedPayloadV1.parseFrom(event.getPayload());
            assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getEventVersion()).isEqualTo(1);
            assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(event.getAggregateType()).isEqualTo("Source");
            assertThat(event.getAggregateId()).isEqualTo(first.sourceId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
            assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
            assertThat(payload.getSourceId()).isEqualTo(first.sourceId().toString());assertThat(payload.getVisibility()).isEqualTo("PRIVATE");assertThat(payload.getVersion()).isEqualTo(i+1L);
            assertThat(payload.getTitle()).isEqualTo(i==0?"원본 문헌":"Printed reference");assertThat(payload.getUrl()).isEqualTo(i==0?"https://example.org/original#page-1":"");
            assertThat(payload.getCitation()).isEqualTo(i==0?"  원본\n인용문  ":"Author, Book, 2026");assertThat(payload.getLicenseNote()).isEqualTo(i==0?"  배포 권리 미확인  ":"Manual license annotation");
            assertThat(payload.getState()).isEqualTo(i==2?"RETIRED":"ACTIVE");assertThat(payload.getRevisionId()).isEqualTo(revisions.get(2-i).revisionId().toString());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='source' AND schema_group='knowledge' AND schema_artifact='source-events' AND schema_version='1'",Long.class,first.sourceId())).isEqualTo(3);
    }

    @Test void concurrentEditorsAndRetirementHaveOneVersionWinnerWithoutLostRevisions() throws Exception {
        var entry=create("Before");try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<6;i++){final int editor=i;tasks.add(workers.submit(()->{start.await();try{store.change(space,entry.sourceId(),actor,1,"Editor "+editor,null,"Printed citation "+editor,"",false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));}
            start.countDown();var codes=new ArrayList<Integer>();for(var task:tasks)codes.add(task.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsOnly(200,412);assertThat(Collections.frequency(codes,200)).isEqualTo(1);
            var current=store.get(space,entry.sourceId());assertThat(current.version()).isEqualTo(2);assertThat(current.title()).startsWith("Editor ");
            assertThat(store.history(space,entry.sourceId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(eventCount(entry.sourceId())).isEqualTo(2);
            var race=new CountDownLatch(1);
            var update=workers.submit(()->{race.await();try{store.change(space,entry.sourceId(),actor,2,"Final update",null,"Citation","",false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            var retire=workers.submit(()->{race.await();try{store.change(space,entry.sourceId(),actor,2,null,null,null,null,true,trace);return 204;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            race.countDown();int updateCode=update.get(20,TimeUnit.SECONDS),retireCode=retire.get(20,TimeUnit.SECONDS);
            assertThat(updateCode).isIn(200,404,412);assertThat(retireCode).isIn(204,412);
            assertThat((updateCode==200?1:0)+(retireCode==204?1:0)).isEqualTo(1);
            assertThat(store.history(space,entry.sourceId(),Long.MAX_VALUE,10)).hasSize(3);assertThat(eventCount(entry.sourceId())).isEqualTo(3);
            if(retireCode==204)status(404,()->store.get(space,entry.sourceId()));else assertThat(store.get(space,entry.sourceId()).title()).isEqualTo("Final update");
        }
    }

    @Test void outboxFailureRollsBackCreateUpdateRetirementAndRevisionTogether() {
        var first=create("Before");jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_source_trace CHECK(trace_id <> 'dddddddddddddddddddddddddddddddd') NOT VALID");
        try {
            assertThatThrownBy(()->store.change(space,first.sourceId(),actor,1,"Lost",null,"Lost citation","Lost license",false,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,first.sourceId())).isEqualTo(first);assertThat(store.history(space,first.sourceId(),Long.MAX_VALUE,10)).hasSize(1);assertThat(eventCount(first.sourceId())).isEqualTo(1);
            assertThatThrownBy(()->store.create(space,actor,"Lost create",null,"Citation","","d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.list(space,null,20).items()).extracting(SourceStore.Source::sourceId).containsExactly(first.sourceId());
            assertThatThrownBy(()->store.change(space,first.sourceId(),actor,1,null,null,null,null,true,"d".repeat(32))).isInstanceOf(DataAccessException.class);
            assertThat(store.get(space,first.sourceId()).state()).isEqualTo("ACTIVE");assertThat(spaceEvents()).isEqualTo(1);
        } finally {jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_source_trace");}
        assertThat(store.change(space,first.sourceId(),actor,1,"After",null,"Printed citation","",false,trace).version()).isEqualTo(2);
    }

    @Test void sourceIsStandaloneAndStableActiveKeysetNeverIncludesForeignOrRetiredRows() {
        assertThat(store.list(space,null,20).items()).isEmpty();var first=create("Same title");var second=create("Same title");var third=create("Third");
        assertThat(first.sourceId()).isNotEqualTo(second.sourceId());UUID foreignSpace=ids.next();var secret=store.create(foreignSpace,actor,"Foreign source secret",null,"Private citation","",trace);
        var page=store.list(space,null,1);assertThat(page.items()).hasSize(1);assertThat(page.nextAfter()).isNotNull();
        var remaining=store.list(space,page.nextAfter(),100);assertThat(remaining.items()).hasSize(2);assertThat(remaining.nextAfter()).isNull();
        var read=new HashSet<UUID>();page.items().forEach(s->read.add(s.sourceId()));remaining.items().forEach(s->read.add(s.sourceId()));
        assertThat(read).containsExactlyInAnyOrder(first.sourceId(),second.sourceId(),third.sourceId()).doesNotContain(secret.sourceId());
        store.change(space,second.sourceId(),actor,1,null,null,null,null,true,trace);
        assertThat(store.list(space,null,100).items()).extracting(SourceStore.Source::sourceId).containsExactly(first.sourceId(),third.sourceId());
        status(404,()->store.get(foreignSpace,first.sourceId()));status(404,()->store.get(space,secret.sourceId()));
        status(404,()->store.history(foreignSpace,first.sourceId(),Long.MAX_VALUE,10));status(404,()->store.history(space,secret.sourceId(),Long.MAX_VALUE,10));
        status(404,()->store.change(foreignSpace,first.sourceId(),actor,1,"Hack",null,"Citation","",false,trace));
        status(404,()->store.change(space,secret.sourceId(),actor,1,null,null,null,null,true,trace));assertThat(store.get(foreignSpace,secret.sourceId()).title()).isEqualTo("Foreign source secret");
        assertThat(store.history(space,second.sourceId(),Long.MAX_VALUE,10)).hasSize(2);
    }

    @Test void unicodeCodePointBoundariesTrimOnlyTitleAndUrlAndPreserveRawCitationLicense() {
        String title="😀".repeat(200),citation="😀".repeat(4000),license="😀".repeat(1000);
        var exact=store.create(space,actor," \t"+title+" \n","  ",citation,license,trace);
        assertThat(exact.title()).isEqualTo(title);assertThat(exact.url()).isNull();assertThat(exact.citation()).isEqualTo(citation);assertThat(exact.licenseNote()).isEqualTo(license);
        status(400,()->store.create(space,actor,title+"😀",null,"Citation","",trace));
        status(400,()->store.create(space,actor,"Title",null,citation+"😀","",trace));
        status(400,()->store.create(space,actor,"Title",null,"Citation",license+"😀",trace));
        for(String broken:List.of("bad\u0000text","bad\ud800text","bad\udc00text")) {
            status(400,()->store.create(space,actor,broken,null,"Citation","",trace));status(400,()->store.create(space,actor,"Title",null,broken,"",trace));
            status(400,()->store.create(space,actor,"Title",null,"Citation",broken,trace));
        }
        for(String titleValue:List.of(""," \t\n"))status(400,()->store.create(space,actor,titleValue,null,"Citation","",trace));
        status(400,()->store.create(space,actor,null,null,"Citation","",trace));status(400,()->store.create(space,actor,"Title",null,null,"",trace));
        status(400,()->store.create(space,actor,"Title",null,"Citation",null,trace));
        status(400,()->store.create(space,actor,"Title",null," \t\n","License only is not provenance",trace));
        status(400,()->store.create(space,actor,"Title"," \t\n","","",trace));assertThat(spaceEvents()).isEqualTo(1);
        status(400,()->store.change(space,exact.sourceId(),actor,1,title+"😀",null,"Citation","",false,trace));
        assertThat(store.get(space,exact.sourceId())).isEqualTo(exact);assertThat(store.history(space,exact.sourceId(),Long.MAX_VALUE,10)).hasSize(1);
    }

    @Test void manuallyEnteredUrlsRequireAbsoluteAsciiHttpHostWithoutCredentialsOrAmbiguousPorts() {
        for(String url:List.of("https://example.org/path?q=one%20two#section","HTTP://example.org:80/reference","https://[::1]:65535/path","http://127.0.0.1:1/","https://localhost:8443/")) {
            var saved=store.create(space,actor,"URL reference","  "+url+"  ","","",trace);assertThat(saved.url()).isEqualTo(url);assertThat(saved.citation()).isEmpty();
        }
        String longest="https://example.org/"+"x".repeat(2048-"https://example.org/".length());
        assertThat(store.create(space,actor,"Longest URL",longest,"","",trace).url()).hasSize(2048);
        for(String url:List.of(longest+"x","//example.org/path","/relative","https:","https:///path","ftp://example.org/file","file:///tmp/file","javascript:alert(1)",
            "https://user:password@example.org/","https://user@example.org/","https://example.org:0/","https://example.org:65536/","https://example.org:-1/","https://example.org:abc/",
            "http://example.org:","http://[::1]:/","http://[fe80::1%25eth0]/","http://[fe80::1%eth0]/",
            "https://example.org/white space","https://example.org/\nline","https://example.org/\tline","https://example.org/\u0000line","https://example.org\\path","https://example.org/한글","https://예시.org/","https://example.org/%zz"))
            status(400,()->store.create(space,actor,"Rejected URL",url,"Nonempty citation does not permit unsafe URL","",trace));
        assertThat(spaceEvents()).isEqualTo(6);
    }

    @Test void retiredSourcesCannotBeEditedOrRevivedAndOverflowNeverEmitsFacts() {
        var entry=create("Retired");store.change(space,entry.sourceId(),actor,1,null,null,null,null,true,trace);
        status(404,()->store.change(space,entry.sourceId(),actor,2,"Revive",null,"Citation","",false,trace));
        status(404,()->store.change(space,entry.sourceId(),actor,2,null,null,null,null,true,trace));
        assertThat(store.history(space,entry.sourceId(),Long.MAX_VALUE,10)).hasSize(2);assertThat(eventCount(entry.sourceId())).isEqualTo(2);
        var huge=create("Overflow");jdbc.update("UPDATE knowledge_source SET version=? WHERE source_id=?",Long.MAX_VALUE,huge.sourceId());
        status(409,()->store.change(space,huge.sourceId(),actor,Long.MAX_VALUE,"Lost",null,"Citation","",false,trace));
        status(409,()->store.change(space,huge.sourceId(),actor,Long.MAX_VALUE,null,null,null,null,true,trace));
        assertThat(store.get(space,huge.sourceId()).version()).isEqualTo(Long.MAX_VALUE);assertThat(store.history(space,huge.sourceId(),Long.MAX_VALUE,10)).hasSize(1);assertThat(eventCount(huge.sourceId())).isEqualTo(1);
    }

    @Test void databaseConstraintsEnforcePrivateLifecyclePositiveVersionAndUniqueAuditVersion() {
        var entry=create("Constraints");assertThatThrownBy(()->jdbc.update("UPDATE knowledge_source SET visibility='PUBLIC' WHERE source_id=?",entry.sourceId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE knowledge_source SET state='UNKNOWN' WHERE source_id=?",entry.sourceId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE knowledge_source SET version=0 WHERE source_id=?",entry.sourceId())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO source_revision(revision_id,source_id,title,url,citation,license_note,version,state,actor_id,occurred_at) VALUES (?,?,?,?,?,?,1,'ACTIVE',?,now())",ids.next(),entry.sourceId(),"Duplicate",null,"Citation","",actor)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO source_revision(revision_id,source_id,title,url,citation,license_note,version,state,actor_id,occurred_at) VALUES (?,?,?,?,?,?,1,'ACTIVE',?,now())",ids.next(),ids.next(),"Orphan",null,"Citation","",actor)).isInstanceOf(DataAccessException.class);
        assertThat(store.get(space,entry.sourceId())).isEqualTo(entry);assertThat(eventCount(entry.sourceId())).isEqualTo(1);
    }

    @Test void invalidIdsCursorsVersionsAndTraceNeverCreateSourceFacts() {
        status(400,()->store.create(UUID.randomUUID(),actor,"Invalid",null,"Citation","",trace));status(400,()->store.create(space,UUID.randomUUID(),"Invalid",null,"Citation","",trace));
        for(String wrongTrace:List.of("","f","F".repeat(32),"0".repeat(32)))assertThatThrownBy(()->store.create(space,actor,"Invalid trace",null,"Citation","",wrongTrace)).isInstanceOf(IllegalArgumentException.class);
        status(400,()->store.get(space,UUID.randomUUID()));status(400,()->store.list(space,UUID.randomUUID(),20));
        status(400,()->store.list(space,null,0));status(400,()->store.list(space,null,101));assertThat(spaceEvents()).isZero();var entry=create("Valid");
        status(400,()->store.history(space,entry.sourceId(),0,1));status(400,()->store.history(space,entry.sourceId(),Long.MAX_VALUE,11));
        status(400,()->store.change(space,entry.sourceId(),actor,0,"Invalid version",null,"Citation","",false,trace));assertThat(eventCount(entry.sourceId())).isEqualTo(1);
    }
}
