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
class RetiredConceptStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;ConceptStore concepts;String schema;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor;String trace="c".repeat(32);
    @BeforeEach void setup(){
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        schema="retired_concept_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(source).execute("CREATE SCHEMA "+schema);source.setCurrentSchema(schema);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);concepts=new ConceptStore(source,Clock.systemUTC());space=ids.next();actor=ids.next();
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    @Test void retiredPagesAndOriginalRevisionsSurviveReloadWithoutChangingCanonicalData(){
        var expected=new ArrayList<UUID>();
        for(int n=0;n<23;n++){var c=concepts.create(space,actor,"Archive "+n,"Original",trace);concepts.change(space,c.conceptId(),actor,1,"Final "+n,"Final description",false,trace);concepts.change(space,c.conceptId(),actor,2,null,null,true,trace);expected.add(c.conceptId());}
        var active=concepts.create(space,actor,"Active","",trace);
        var revisions=concepts.history(space,expected.getFirst(),Long.MAX_VALUE,10);var outbox=jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id");
        var first=concepts.retired(space,null,20);assertThat(first.items()).extracting(ConceptStore.Summary::conceptId).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19));
        concepts=new ConceptStore(source,Clock.systemUTC());var second=concepts.retired(space,first.nextAfter(),20);assertThat(second.items()).extracting(ConceptStore.Summary::conceptId).containsExactlyElementsOf(expected.subList(20,23));assertThat(second.nextAfter()).isNull();
        assertThat(concepts.retired(space,expected.getLast(),1).items()).isEmpty();assertThat(concepts.retired(space,null,1).items()).hasSize(1);
        assertThat(first.items().getFirst().title()).isEqualTo("Final 0");assertThat(first.items().getFirst().version()).isEqualTo(3);
        assertThat(concepts.list(space,null,20).items()).extracting(ConceptStore.Summary::conceptId).containsExactly(active.conceptId());
        assertThat(concepts.history(space,expected.getFirst(),Long.MAX_VALUE,10)).isEqualTo(revisions);assertThat(revisions).extracting(ConceptStore.Revision::version).containsExactly(3L,2L,1L);
        status(404,()->concepts.get(space,expected.getFirst()));status(404,()->concepts.change(space,expected.getFirst(),actor,3,"Restore","",false,trace));
        assertThat(jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id")).isEqualTo(outbox);
    }
    @Test void archiveExcludesOtherSpacesAndPublicConceptsAndRejectsBadBounds(){
        var c=concepts.create(space,actor,"Private","",trace);concepts.change(space,c.conceptId(),actor,1,null,null,true,trace);
        UUID other=ids.next();var foreign=concepts.create(other,actor,"Foreign","",trace);concepts.change(other,foreign.conceptId(),actor,1,null,null,true,trace);
        var publicConcept=concepts.createWithId(space,ids.next(),actor,"Public","",trace,"PUBLIC");concepts.changeScoped(space,publicConcept.conceptId(),actor,1,null,null,true,trace,"PUBLIC");
        assertThat(concepts.retired(space,null,100).items()).extracting(ConceptStore.Summary::conceptId).containsExactly(c.conceptId());assertThat(concepts.retired(ids.next(),null,20).items()).isEmpty();
        status(404,()->concepts.history(other,c.conceptId(),Long.MAX_VALUE,5));
        for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE})status(400,()->concepts.retired(space,null,limit));
        status(400,()->concepts.retired(space,UUID.randomUUID(),20));status(400,()->concepts.retired(UUID.randomUUID(),null,20));
    }
}
