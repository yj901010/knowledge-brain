package io.knowledgebrain.knowledge;

import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class RetiredCitationStoreIntegrationTest {
    PGSimpleDataSource data;JdbcTemplate jdbc;CitationStore store;ConceptStore concepts;ClaimStore claims;SourceStore sources;String schema;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,subject,predicate;ClaimStore.Claim claim;String trace="e".repeat(32);
    @BeforeEach void setup(){
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        schema="retired_citation_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);data.setCurrentSchema(schema);
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);store=new CitationStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());claims=new ClaimStore(data,Clock.systemUTC());sources=new SourceStore(data,Clock.systemUTC());space=ids.next();actor=ids.next();
        subject=concepts.create(space,actor,"Subject","",trace).conceptId();predicate=concepts.create(space,actor,"Predicate","",trace).conceptId();claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("NUMBER",null,"9007199254740993.1200"),trace);
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    SourceStore.Source source(){return sources.create(space,actor,"Pinned 🧠",null," \nExact citation "," \tExact license\n",trace);}
    CitationStore.Citation create(SourceStore.Source source){return store.create(space,subject,claim.claimId(),actor,new CitationStore.Reference(source.sourceId(),source.version(),claim.version()),trace);}
    CitationStore.Citation retire(CitationStore.Citation citation){return store.change(space,subject,claim.claimId(),citation.citationId(),actor,citation.version(),null,true,trace);}
    @Test void rediscoveryPaginatesExactImmutablePinsAfterParentsRetireAndNeverWrites(){
        var s=source();var expected=new ArrayList<CitationStore.Citation>();
        for(int n=0;n<23;n++)expected.add(retire(create(s)));
        var active=create(s);assertThat(active.citationId()).isNotIn(expected.stream().map(CitationStore.Citation::citationId).toList());
        assertThat(store.list(space,subject,claim.claimId(),null,20).items()).containsExactly(active);assertThat(store.bySource(space,s.sourceId(),null,20).items()).extracting(CitationStore.SourceCitation::citationId).containsExactly(active.citationId());
        var history=store.history(space,subject,claim.claimId(),expected.getFirst().citationId(),Long.MAX_VALUE,10);
        sources.change(space,s.sourceId(),actor,1,"Changed",null,"New citation","New rights",false,trace);sources.change(space,s.sourceId(),actor,2,null,null,null,null,true,trace);
        claims.change(space,subject,claim.claimId(),actor,1,null,null,true,trace);concepts.change(space,subject,actor,1,null,null,true,trace);concepts.change(space,predicate,actor,1,null,null,true,trace);
        var outbox=jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id");var stored=jdbc.queryForList("SELECT * FROM claim_citation ORDER BY citation_id");
        var first=store.retired(space,subject,claim.claimId(),null,20);assertThat(first.items()).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19).citationId());
        store=new CitationStore(data,Clock.systemUTC());var next=store.retired(space,subject,claim.claimId(),first.nextAfter(),20);assertThat(next.items()).containsExactlyElementsOf(expected.subList(20,23));assertThat(next.nextAfter()).isNull();
        assertThat(store.retired(space,subject,claim.claimId(),expected.getLast().citationId(),1).items()).isEmpty();assertThat(first.items()).allMatch(c->c.state().equals("RETIRED")&&c.source().version()==1&&c.claim().object().equals(claim.object()));
        assertThat(store.history(space,subject,claim.claimId(),expected.getFirst().citationId(),Long.MAX_VALUE,10)).isEqualTo(history);assertThat(store.bySource(space,s.sourceId(),null,20).items()).isEmpty();
        status(404,()->store.get(space,subject,claim.claimId(),active.citationId()));status(404,()->store.get(space,subject,claim.claimId(),expected.getFirst().citationId()));
        assertThat(jdbc.queryForList("SELECT * FROM claim_citation ORDER BY citation_id")).isEqualTo(stored);assertThat(jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id")).isEqualTo(outbox);
    }
    @Test void archiveRetainsRepinsButEnforcesSpaceParentVisibilityAndCursorBounds(){
        var s=source();var first=create(s);var other=source();var repinned=store.change(space,subject,claim.claimId(),first.citationId(),actor,1,new CitationStore.Reference(other.sourceId(),1,1),false,trace);var archived=retire(repinned);
        assertThat(store.retired(space,subject,claim.claimId(),null,1).items()).containsExactly(archived);assertThat(store.history(space,subject,claim.claimId(),archived.citationId(),Long.MAX_VALUE,10)).extracting(r->r.source().sourceId()).containsExactly(other.sourceId(),other.sourceId(),s.sourceId());
        for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE})status(400,()->store.retired(space,subject,claim.claimId(),null,limit));status(400,()->store.retired(space,subject,claim.claimId(),UUID.randomUUID(),20));
        status(404,()->store.retired(ids.next(),subject,claim.claimId(),null,20));status(404,()->store.retired(space,predicate,claim.claimId(),null,20));status(404,()->store.retired(space,subject,ids.next(),null,20));
        jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",subject);status(404,()->store.retired(space,subject,claim.claimId(),null,20));jdbc.update("UPDATE concept SET visibility='PRIVATE' WHERE concept_id=?",subject);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='claim_citation_claim_retired_idx'",String.class,schema)).contains("space_id, subject_concept_id, claim_id, citation_id").contains("RETIRED");
    }
}
