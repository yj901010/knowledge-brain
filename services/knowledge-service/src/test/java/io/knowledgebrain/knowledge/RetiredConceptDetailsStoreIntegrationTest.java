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
class RetiredConceptDetailsStoreIntegrationTest {
    PGSimpleDataSource data;JdbcTemplate jdbc;AliasStore aliases;ExternalMappingStore mappings;ConceptStore concepts;SourceStore sources;String schema;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,concept;String trace="e".repeat(32);
    @BeforeEach void setup(){
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        schema="retired_details_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);data.setCurrentSchema(schema);
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);aliases=new AliasStore(data,Clock.systemUTC());mappings=new ExternalMappingStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());sources=new SourceStore(data,Clock.systemUTC());space=ids.next();actor=ids.next();concept=concepts.create(space,actor,"Original Concept","Original body",trace).conceptId();
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    List<Map<String,Object>> outbox(){return jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id");}
    @Test void retiredAliasesRediscoverDistinctRepeatedLabelsAndImmutableHistoryAfterParentRetirement(){
        var expected=new ArrayList<AliasStore.Alias>();
        for(int n=0;n<23;n++){
            var a=aliases.create(space,concept,actor,"Repeated alias",trace);
            if(n==0)for(int v=1;v<6;v++)a=aliases.change(space,concept,a.aliasId(),actor,v,"Earlier 🧠 alias "+v,false,trace);
            expected.add(aliases.change(space,concept,a.aliasId(),actor,a.version(),null,true,trace));
        }
        var active=aliases.create(space,concept,actor,"Repeated alias",trace);assertThat(aliases.list(space,concept,null,20).items()).containsExactly(active);
        var firstId=expected.getFirst().aliasId();var history=aliases.history(space,concept,firstId,Long.MAX_VALUE,10);assertThat(history).hasSize(7);assertThat(history.getLast().label()).isEqualTo("Repeated alias");
        concepts.change(space,concept,actor,1,null,null,true,trace);var events=outbox();var stored=jdbc.queryForList("SELECT * FROM alias ORDER BY alias_id");
        var first=aliases.retired(space,concept,null,20);assertThat(first.items()).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19).aliasId());aliases=new AliasStore(data,Clock.systemUTC());
        var next=aliases.retired(space,concept,first.nextAfter(),20);assertThat(next.items()).containsExactlyElementsOf(expected.subList(20,23));assertThat(next.nextAfter()).isNull();assertThat(aliases.retired(space,concept,expected.getLast().aliasId(),1).items()).isEmpty();
        assertThat(aliases.history(space,concept,firstId,Long.MAX_VALUE,5)).containsExactlyElementsOf(history.subList(0,5));assertThat(aliases.history(space,concept,firstId,3,5)).containsExactlyElementsOf(history.subList(5,7));
        status(404,()->aliases.list(space,concept,null,20));status(404,()->aliases.get(space,concept,active.aliasId()));status(404,()->aliases.change(space,concept,firstId,actor,7,"Restore",false,trace));
        for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE})status(400,()->aliases.retired(space,concept,null,limit));status(400,()->aliases.retired(space,concept,UUID.randomUUID(),20));status(404,()->aliases.retired(ids.next(),concept,null,20));status(404,()->aliases.retired(space,ids.next(),null,20));
        assertThat(jdbc.queryForList("SELECT * FROM alias ORDER BY alias_id")).isEqualTo(stored);assertThat(outbox()).isEqualTo(events);
        jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",concept);status(404,()->aliases.retired(space,concept,null,20));
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='alias_retired_idx'",String.class,schema)).contains("space_id, concept_id, alias_id").contains("RETIRED");
    }
    @Test void retiredMappingsPreserveOpaqueIdentifiersEditionsAndBothHistoricalPinsAcrossRetirement(){
        var source=sources.create(space,actor,"Original Source","https://example.invalid/original","Original citation","License A",trace);
        var other=sources.create(space,actor,"Other Source",null,"Other citation","License B",trace);
        var ref=new ExternalMappingStore.Edit("Case-System","00000000000000000000003","","Original label",source.sourceId(),1,1);
        var expected=new ArrayList<ExternalMappingStore.Mapping>();
        for(int n=0;n<23;n++){
            var m=mappings.create(space,concept,actor,ref,trace);
            if(n==0)for(int v=1;v<6;v++)m=mappings.change(space,concept,m.mappingId(),actor,v,new ExternalMappingStore.Edit("Case-System","00000000000000000000003","Edition "+v,"Label "+v,other.sourceId(),1,1),false,trace);
            expected.add(mappings.change(space,concept,m.mappingId(),actor,m.version(),null,true,trace));
        }
        var active=mappings.create(space,concept,actor,ref,trace);assertThat(mappings.list(space,concept,null,20).items()).containsExactly(active);
        var firstId=expected.getFirst().mappingId();var history=mappings.history(space,concept,firstId,Long.MAX_VALUE,10);assertThat(history).hasSize(7);assertThat(history.getLast().source().sourceId()).isEqualTo(source.sourceId());assertThat(history.getFirst().source().sourceId()).isEqualTo(other.sourceId());assertThat(history.getLast().systemVersion()).isEmpty();
        concepts.change(space,concept,actor,1,"Later Concept","Later description",false,trace);sources.change(space,source.sourceId(),actor,1,"Later source",null,"Changed citation","Changed license",false,trace);
        sources.change(space,source.sourceId(),actor,2,null,null,null,null,true,trace);sources.change(space,other.sourceId(),actor,1,null,null,null,null,true,trace);concepts.change(space,concept,actor,2,null,null,true,trace);
        var events=outbox();var stored=jdbc.queryForList("SELECT * FROM external_mapping ORDER BY mapping_id");var first=mappings.retired(space,concept,null,20);assertThat(first.items()).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19).mappingId());mappings=new ExternalMappingStore(data,Clock.systemUTC());
        var next=mappings.retired(space,concept,first.nextAfter(),20);assertThat(next.items()).containsExactlyElementsOf(expected.subList(20,23));assertThat(next.nextAfter()).isNull();assertThat(mappings.retired(space,concept,expected.getLast().mappingId(),1).items()).isEmpty();
        assertThat(mappings.history(space,concept,firstId,Long.MAX_VALUE,10)).isEqualTo(history);assertThat(mappings.history(space,concept,firstId,3,5)).containsExactlyElementsOf(history.subList(5,7));assertThat(first.items()).allMatch(m->m.mappingState().equals("CANDIDATE")&&m.concept().title().equals("Original Concept")&&m.code().equals("00000000000000000000003"));
        status(404,()->mappings.list(space,concept,null,20));status(404,()->mappings.get(space,concept,active.mappingId()));status(404,()->mappings.change(space,concept,firstId,actor,7,ref,false,trace));
        for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE})status(400,()->mappings.retired(space,concept,null,limit));status(400,()->mappings.retired(space,concept,UUID.randomUUID(),20));status(404,()->mappings.retired(ids.next(),concept,null,20));status(404,()->mappings.retired(space,ids.next(),null,20));
        assertThat(jdbc.queryForList("SELECT * FROM external_mapping ORDER BY mapping_id")).isEqualTo(stored);assertThat(outbox()).isEqualTo(events);jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",concept);status(404,()->mappings.retired(space,concept,null,20));
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='external_mapping_retired_idx'",String.class,schema)).contains("space_id, concept_id, mapping_id").contains("RETIRED");
    }
}
