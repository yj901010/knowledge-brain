package io.knowledgebrain.knowledge;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class ExternalMappingStoreIntegrationTest {
    PGSimpleDataSource data;JdbcTemplate jdbc;ExternalMappingStore store;ConceptStore concepts;SourceStore sources;
    UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="b".repeat(32);UUID space,actor,concept;SourceStore.Source source;
    @BeforeEach void prepare() {
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);store=new ExternalMappingStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());sources=new SourceStore(data,Clock.systemUTC());
        space=ids.next();actor=ids.next();concept=concepts.create(space,actor,"Original Concept","Original meaning",trace).conceptId();source=sources.create(space,actor,"Original Source","https://example.invalid/original","  Original citation  ","Manual license",trace);
    }
    ExternalMappingStore.Edit edit(String code,long cv,long sv){return ExternalMappingStore.edit("Manual System",code,"2026","External label",source.sourceId(),sv,cv);}
    ExternalMappingStore.Mapping create(){return store.create(space,concept,actor,edit("CODE-1",1,1),trace);}
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));}
    long events(UUID id){return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='external-mapping'",Long.class,id);}
    @Test void immutablePinsRevisionsAndIndependentCandidateStateSurviveEdits() {
        var m=create();assertThat(m.mappingId().version()).isEqualTo(7);assertThat(m.mappingState()).isEqualTo("CANDIDATE");assertThat(m.visibility()).isEqualTo("PRIVATE");
        concepts.change(space,concept,actor,1,"Changed Concept","Changed meaning",false,trace);
        sources.change(space,source.sourceId(),actor,1,"Changed Source",null,"New citation","New license",false,trace);
        assertThat(store.get(space,concept,m.mappingId())).isEqualTo(m);
        status(412,()->store.change(space,concept,m.mappingId(),actor,1,edit("CODE-2",1,2),false,trace));
        status(412,()->store.change(space,concept,m.mappingId(),actor,1,edit("CODE-2",2,1),false,trace));
        var changed=store.change(space,concept,m.mappingId(),actor,1,edit("CODE-2",2,2),false,trace);
        assertThat(changed.concept().description()).isEqualTo("Changed meaning");assertThat(changed.source().citation()).isEqualTo("New citation");
        assertThat(concepts.get(space,concept).version()).isEqualTo(2);assertThat(sources.get(space,source.sourceId()).version()).isEqualTo(2);
        store.change(space,concept,m.mappingId(),actor,2,null,true,trace);status(404,()->store.get(space,concept,m.mappingId()));
        var history=store.history(space,concept,m.mappingId(),Long.MAX_VALUE,10);
        assertThat(history).extracting(ExternalMappingStore.Revision::version).containsExactly(3L,2L,1L);
        assertThat(history.getLast().concept()).isEqualTo(m.concept());assertThat(history.getLast().source()).isEqualTo(m.source());assertThat(events(m.mappingId())).isEqualTo(3);
        assertThat(new ExternalMappingStore(data,Clock.systemUTC()).history(space,concept,m.mappingId(),Long.MAX_VALUE,10)).isEqualTo(history);
    }
    @Test void scopesRetiredSourcesAndKnownHistoryArePreservedWithoutNewInvalidWrites() {
        var m=create();UUID foreignSpace=ids.next();var foreign=sources.create(foreignSpace,actor,"Other",null,"Other citation","",trace);
        status(404,()->store.create(space,concept,actor,ExternalMappingStore.edit("Other","code","","",foreign.sourceId(),1,1),trace));
        status(404,()->store.history(foreignSpace,concept,m.mappingId(),Long.MAX_VALUE,5));
        status(404,()->store.get(space,ids.next(),m.mappingId()));
        sources.change(space,source.sourceId(),actor,1,null,null,null,null,true,trace);
        assertThat(store.get(space,concept,m.mappingId())).isEqualTo(m);
        status(404,()->store.change(space,concept,m.mappingId(),actor,1,edit("CODE-2",1,1),false,trace));
        store.change(space,concept,m.mappingId(),actor,1,null,true,trace);
        var history=store.history(space,concept,m.mappingId(),Long.MAX_VALUE,5);
        concepts.change(space,concept,actor,1,null,null,true,trace);
        status(404,()->store.list(space,concept,null,20));
        assertThat(store.history(space,concept,m.mappingId(),Long.MAX_VALUE,5)).isEqualTo(history);
    }
    @Test void duplicateCreateAndConcurrentOptimisticEditsHaveOneWinner()throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{gate.await();try{create();return 201;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        var m=store.list(space,concept,null,20).items().getFirst();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(String code:List.of("2","3"))tasks.add(pool.submit(()->{gate.await();try{store.change(space,concept,m.mappingId(),actor,1,edit(code,1,1),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(events(m.mappingId())).isEqualTo(2);
    }
    @Test void pagesHistoryEditionsAndRetiredIdentifiersAreIndependent() {
        for(int i=0;i<22;i++)store.create(space,concept,actor,edit("code "+i,1,1),trace);
        var first=store.list(space,concept,null,20);assertThat(first.items()).hasSize(20);assertThat(first.nextAfter()).isNotNull();
        assertThat(store.list(space,concept,first.nextAfter(),20).items()).hasSize(2);
        var m=first.items().getFirst();
        for(int v=1;v<=7;v++)store.change(space,concept,m.mappingId(),actor,v,edit("updated "+v,1,1),false,trace);
        assertThat(store.history(space,concept,m.mappingId(),Long.MAX_VALUE,5)).extracting(ExternalMappingStore.Revision::version).containsExactly(8L,7L,6L,5L,4L);
        assertThat(store.history(space,concept,m.mappingId(),4,5)).extracting(ExternalMappingStore.Revision::version).containsExactly(3L,2L,1L);
        var e=ExternalMappingStore.edit("Manual System","updated 7","","",source.sourceId(),1,1);assertThat(store.create(space,concept,actor,e,trace).systemVersion()).isEmpty();
        store.change(space,concept,m.mappingId(),actor,8,null,true,trace);assertThat(store.create(space,concept,actor,edit("updated 7",1,1),trace).mappingId()).isNotEqualTo(m.mappingId());
    }
    @Test void canonicalRowsRevisionsAndEventsRollbackTogether()throws Exception {
        UUID mapping;
        try(var connection=data.getConnection()){
            connection.setAutoCommit(false);var shared=new SingleConnectionDataSource(connection,true);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(shared));
            mapping=tx.execute(status->{var m=new ExternalMappingStore(shared,Clock.systemUTC()).create(space,concept,actor,edit("rollback",1,1),trace);status.setRollbackOnly();return m.mappingId();});
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_mapping WHERE mapping_id=?",Long.class,mapping)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM external_mapping_revision WHERE mapping_id=?",Long.class,mapping)).isZero();assertThat(events(mapping)).isZero();
    }
}
