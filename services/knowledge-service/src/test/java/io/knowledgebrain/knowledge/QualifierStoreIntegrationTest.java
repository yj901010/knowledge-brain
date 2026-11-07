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
class QualifierStoreIntegrationTest {
    PGSimpleDataSource data;JdbcTemplate jdbc;QualifierStore store;ConceptStore concepts;ClaimStore claims;
    UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="d".repeat(32);UUID space,actor,subject,predicate,object;ClaimStore.Claim claim;
    @BeforeEach void prepare() {
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);
        store=new QualifierStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());claims=new ClaimStore(data,Clock.systemUTC());
        space=ids.next();actor=ids.next();subject=concept("subject");predicate=concept("predicate");object=concept("object");
        claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",object),trace);
    }
    UUID concept(String title){return concepts.create(space,actor,title,"",trace).conceptId();}
    QualifierStore.Edit edit(String type,String value,long version){return QualifierStore.edit(predicate,QualifierStore.value(type,value),version);}
    QualifierStore.Qualifier create(){return store.create(space,subject,claim.claimId(),actor,edit("NUMBER","9007199254740993.123456789012345678",1),trace);}
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));}
    long events(UUID id){return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='qualifier-v2'",Long.class,id);}
    @Test void revisionsPinClaimAndExactValuesWithAtomicEventsAndIndependentVersion() {
        var q=create();assertThat(q.qualifierId().version()).isEqualTo(7);assertThat(q.visibility()).isEqualTo("PRIVATE");
        claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",subject),false,trace);
        assertThat(store.get(space,subject,claim.claimId(),q.qualifierId())).isEqualTo(q);
        status(412,()->store.change(space,subject,claim.claimId(),q.qualifierId(),actor,1,edit("DATE","2026-10-20",1),false,trace));
        var updated=store.change(space,subject,claim.claimId(),q.qualifierId(),actor,1,edit("DATE","2026-10-20",2),false,trace);
        assertThat(updated.claim().version()).isEqualTo(2);assertThat(updated.claim().object().conceptId()).isEqualTo(subject);
        assertThat(claims.get(space,subject,claim.claimId()).version()).isEqualTo(2);
        store.change(space,subject,claim.claimId(),q.qualifierId(),actor,2,null,true,trace);
        status(404,()->store.get(space,subject,claim.claimId(),q.qualifierId()));
        var history=store.history(space,subject,claim.claimId(),q.qualifierId(),Long.MAX_VALUE,10);
        assertThat(history).extracting(QualifierStore.Revision::version).containsExactly(3L,2L,1L);
        assertThat(history.getLast().value()).isEqualTo(q.value());assertThat(history.getLast().claim()).isEqualTo(q.claim());assertThat(events(q.qualifierId())).isEqualTo(3);
        assertThat(new QualifierStore(data,Clock.systemUTC()).history(space,subject,claim.claimId(),q.qualifierId(),Long.MAX_VALUE,10)).isEqualTo(history);
    }
    @Test void privateScopeRetiredReferencesAndKnownHistoryAreProtected() {
        UUID scope=concept("scope"),value=concept("value");var q=store.create(space,subject,claim.claimId(),actor,QualifierStore.edit(scope,QualifierStore.value("CONCEPT",value.toString()),1),trace);
        UUID foreign=concepts.create(ids.next(),actor,"foreign","",trace).conceptId();
        status(404,()->store.create(space,subject,claim.claimId(),actor,QualifierStore.edit(foreign,QualifierStore.value("BOOLEAN","true"),1),trace));
        status(404,()->store.create(space,subject,claim.claimId(),actor,QualifierStore.edit(scope,QualifierStore.value("CONCEPT",foreign.toString()),1),trace));
        status(404,()->store.history(ids.next(),subject,claim.claimId(),q.qualifierId(),Long.MAX_VALUE,5));
        concepts.change(space,value,actor,1,null,null,true,trace);
        assertThat(store.get(space,subject,claim.claimId(),q.qualifierId())).isEqualTo(q);
        status(404,()->store.change(space,subject,claim.claimId(),q.qualifierId(),actor,1,QualifierStore.edit(scope,q.value(),1),false,trace));
        var old=store.history(space,subject,claim.claimId(),q.qualifierId(),Long.MAX_VALUE,5);
        claims.change(space,subject,claim.claimId(),actor,1,null,null,true,trace);
        status(404,()->store.list(space,subject,claim.claimId(),null,20));
        assertThat(store.history(space,subject,claim.claimId(),q.qualifierId(),Long.MAX_VALUE,5)).isEqualTo(old);
    }
    @Test void normalizedDuplicatesAndConcurrentWritesProduceOnlyOneWinner() throws Exception {
        var q=store.create(space,subject,claim.claimId(),actor,edit("NUMBER","1.00",1),trace);
        status(409,()->store.create(space,subject,claim.claimId(),actor,edit("NUMBER","1",1),trace));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Integer>>();
            for(String v:List.of("2","3"))tasks.add(pool.submit(()->{gate.await();try{store.change(space,subject,claim.claimId(),q.qualifierId(),actor,1,edit("NUMBER",v,1),false,trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));
            gate.countDown();assertThat(List.of(tasks.get(0).get(20,TimeUnit.SECONDS),tasks.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(events(q.qualifierId())).isEqualTo(2);
    }
    @Test void pagesAndHistoryHaveStableCursorsAndAllTypesPersist() {
        for(int i=0;i<22;i++)store.create(space,subject,claim.claimId(),actor,edit("STRING","row "+i,1),trace);
        var first=store.list(space,subject,claim.claimId(),null,20);assertThat(first.items()).hasSize(20);assertThat(first.nextAfter()).isNotNull();
        var next=store.list(space,subject,claim.claimId(),first.nextAfter(),20);assertThat(next.items()).hasSize(2);assertThat(next.nextAfter()).isNull();
        var q=first.items().getFirst();
        for(int v=1;v<=7;v++)store.change(space,subject,claim.claimId(),q.qualifierId(),actor,v,edit("NUMBER",""+v,1),false,trace);
        var history=store.history(space,subject,claim.claimId(),q.qualifierId(),Long.MAX_VALUE,5);
        assertThat(history).extracting(QualifierStore.Revision::version).containsExactly(8L,7L,6L,5L,4L);
        assertThat(store.history(space,subject,claim.claimId(),q.qualifierId(),4,5)).extracting(QualifierStore.Revision::version).containsExactly(3L,2L,1L);
        for(var e:List.of(edit("DATE","2024-02-29",1),edit("BOOLEAN","false",1),edit("CONCEPT",object.toString(),1)))assertThat(store.create(space,subject,claim.claimId(),actor,e,trace).value()).isEqualTo(e.value());
    }
    @Test void outerRollbackRetainsNeitherCanonicalSnapshotNorOutbox() throws Exception {
        UUID qualifier;
        try(var connection=data.getConnection()){
            connection.setAutoCommit(false);
            var shared=new SingleConnectionDataSource(connection,true);
            // An explicit outer JDBC rollback covers the complete local ACID write set.
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(shared));
            qualifier=tx.execute(status->{var q=new QualifierStore(shared,Clock.systemUTC()).create(space,subject,claim.claimId(),actor,edit("BOOLEAN","true",1),trace);status.setRollbackOnly();return q.qualifierId();});
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_qualifier WHERE qualifier_id=?",Long.class,qualifier)).isZero();assertThat(events(qualifier)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qualifier_revision WHERE qualifier_id=?",Long.class,qualifier)).isZero();
    }
}
