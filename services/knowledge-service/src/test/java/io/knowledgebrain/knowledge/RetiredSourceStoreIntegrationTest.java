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
class RetiredSourceStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;SourceStore sources;String schema;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor;String trace="d".repeat(32);
    @BeforeEach void setup(){
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        schema="retired_source_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(source).execute("CREATE SCHEMA "+schema);source.setCurrentSchema(schema);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);sources=new SourceStore(source,Clock.systemUTC());space=ids.next();actor=ids.next();
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    @Test void rediscoveryRetainsExactSourceFieldsPinnedCitationsHistoryAndOutbox(){
        var concepts=new ConceptStore(source,Clock.systemUTC());var claims=new ClaimStore(source,Clock.systemUTC());var citations=new CitationStore(source,Clock.systemUTC());
        UUID subject=concepts.create(space,actor,"Subject","",trace).conceptId(),predicate=concepts.create(space,actor,"Predicate","",trace).conceptId();
        var claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("STRING",null,"Exact Claim"),trace);
        var expected=new ArrayList<UUID>();CitationStore.Citation pin=null;
        for(int n=0;n<23;n++){
            var s=sources.create(space,actor,"Same title",null,"Original citation","Original license",trace);expected.add(s.sourceId());
            if(n==0)pin=citations.create(space,subject,claim.claimId(),actor,new CitationStore.Reference(s.sourceId(),1,1),trace);
            sources.change(space,s.sourceId(),actor,1,"Same title",n%2==0?"https://example.invalid/book?q=1#edition":null," \nExact citation 🧠 "," \tExact license\n",false,trace);
            sources.change(space,s.sourceId(),actor,2,null,null,null,null,true,trace);
        }
        var active=sources.create(space,actor,"Active",null,"Active citation","",trace);var revisions=sources.history(space,expected.getFirst(),Long.MAX_VALUE,10);
        var outbox=jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id");var storedCitation=jdbc.queryForList("SELECT * FROM claim_citation ORDER BY citation_id");var citationHistory=citations.history(space,subject,claim.claimId(),pin.citationId(),Long.MAX_VALUE,10);
        var first=sources.retired(space,null,20);assertThat(first.items()).extracting(SourceStore.Source::sourceId).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19));
        sources=new SourceStore(source,Clock.systemUTC());var second=sources.retired(space,first.nextAfter(),20);assertThat(second.items()).extracting(SourceStore.Source::sourceId).containsExactlyElementsOf(expected.subList(20,23));assertThat(second.nextAfter()).isNull();
        assertThat(first.items().getFirst().url()).isEqualTo("https://example.invalid/book?q=1#edition");assertThat(first.items().get(1).url()).isNull();assertThat(first.items().getFirst().citation()).isEqualTo(" \nExact citation 🧠 ");assertThat(first.items().getFirst().licenseNote()).isEqualTo(" \tExact license\n");
        assertThat(first.items()).allMatch(s->s.spaceId().equals(space)&&s.visibility().equals("PRIVATE")&&s.state().equals("RETIRED")&&s.version()==3);
        assertThat(sources.list(space,null,20).items()).extracting(SourceStore.Source::sourceId).containsExactly(active.sourceId());assertThat(sources.retired(space,expected.getLast(),1).items()).isEmpty();
        assertThat(sources.history(space,expected.getFirst(),Long.MAX_VALUE,10)).isEqualTo(revisions);assertThat(citations.bySource(space,expected.getFirst(),null,20).items()).hasSize(1);assertThat(citations.get(space,subject,claim.claimId(),pin.citationId())).isEqualTo(pin);
        assertThat(citations.history(space,subject,claim.claimId(),pin.citationId(),Long.MAX_VALUE,10)).isEqualTo(citationHistory);assertThat(jdbc.queryForList("SELECT * FROM claim_citation ORDER BY citation_id")).isEqualTo(storedCitation);
        status(404,()->sources.get(space,expected.getFirst()));status(404,()->sources.change(space,expected.getFirst(),actor,3,"Restore",null,"Attempt","",false,trace));
        assertThat(jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id")).isEqualTo(outbox);
    }
    @Test void archiveEnforcesSpaceAndBoundedExclusiveCursor(){
        var own=sources.create(space,actor,"Private",null,"Citation","",trace);sources.change(space,own.sourceId(),actor,1,null,null,null,null,true,trace);
        UUID other=ids.next();var foreign=sources.create(other,actor,"Foreign",null,"Citation","",trace);sources.change(other,foreign.sourceId(),actor,1,null,null,null,null,true,trace);
        assertThat(sources.retired(space,null,1).items()).extracting(SourceStore.Source::sourceId).containsExactly(own.sourceId());assertThat(sources.retired(ids.next(),null,20).items()).isEmpty();
        status(404,()->sources.history(other,own.sourceId(),Long.MAX_VALUE,5));for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE})status(400,()->sources.retired(space,null,limit));status(400,()->sources.retired(space,UUID.randomUUID(),20));status(400,()->sources.retired(UUID.randomUUID(),null,20));
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='knowledge_source_space_retired_idx'",String.class,schema)).contains("space_id, source_id").contains("RETIRED");
    }
}
