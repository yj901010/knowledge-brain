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
class RetiredQualifierStoreIntegrationTest {
    PGSimpleDataSource data;JdbcTemplate jdbc;QualifierStore store;ConceptStore concepts;ClaimStore claims;String schema;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,subject,predicate,condition;ClaimStore.Claim claim;String trace="e".repeat(32);
    @BeforeEach void setup(){
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        schema="retired_qualifier_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(data).execute("CREATE SCHEMA "+schema);data.setCurrentSchema(schema);
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);store=new QualifierStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());claims=new ClaimStore(data,Clock.systemUTC());space=ids.next();actor=ids.next();
        subject=concept("Subject");predicate=concept("Predicate");condition=concept("Condition");claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("NUMBER",null,"9007199254740993.1200"),trace);
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    UUID concept(String title){return concepts.create(space,actor,title,"",trace).conceptId();}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    QualifierStore.Edit edit(String type,String value,long version){return new QualifierStore.Edit(condition,QualifierStore.value(type,value),version);}
    QualifierStore.Qualifier create(){return store.create(space,subject,claim.claimId(),actor,edit("NUMBER","9007199254740993.123456789012345678",1),trace);}
    QualifierStore.Qualifier retire(QualifierStore.Qualifier q){return store.change(space,subject,claim.claimId(),q.qualifierId(),actor,q.version(),null,true,trace);}
    @Test void rediscoveryRetainsExactPinsAndDistinctRepeatedConditionsThroughParentRetirementWithoutWrites(){
        var expected=new ArrayList<QualifierStore.Qualifier>();for(int n=0;n<23;n++)expected.add(retire(create()));var active=create();
        assertThat(store.list(space,subject,claim.claimId(),null,20).items()).containsExactly(active);assertThat(active.qualifierId()).isNotIn(expected.stream().map(QualifierStore.Qualifier::qualifierId).toList());
        var history=store.history(space,subject,claim.claimId(),expected.getFirst().qualifierId(),Long.MAX_VALUE,10);
        concepts.change(space,condition,actor,1,null,null,true,trace);claims.change(space,subject,claim.claimId(),actor,1,null,null,true,trace);concepts.change(space,subject,actor,1,null,null,true,trace);concepts.change(space,predicate,actor,1,null,null,true,trace);
        var outbox=jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id");var stored=jdbc.queryForList("SELECT * FROM claim_qualifier ORDER BY qualifier_id");
        var first=store.retired(space,subject,claim.claimId(),null,20);assertThat(first.items()).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19).qualifierId());
        store=new QualifierStore(data,Clock.systemUTC());var next=store.retired(space,subject,claim.claimId(),first.nextAfter(),20);assertThat(next.items()).containsExactlyElementsOf(expected.subList(20,23));assertThat(next.nextAfter()).isNull();
        assertThat(store.retired(space,subject,claim.claimId(),expected.getLast().qualifierId(),1).items()).isEmpty();assertThat(first.items()).allMatch(q->q.state().equals("RETIRED")&&q.claim().object().equals(claim.object())&&q.value().value().equals("9007199254740993.123456789012345678"));
        assertThat(store.history(space,subject,claim.claimId(),expected.getFirst().qualifierId(),Long.MAX_VALUE,10)).isEqualTo(history);status(404,()->store.list(space,subject,claim.claimId(),null,20));
        status(404,()->store.get(space,subject,claim.claimId(),active.qualifierId()));status(404,()->store.get(space,subject,claim.claimId(),expected.getFirst().qualifierId()));
        assertThat(jdbc.queryForList("SELECT * FROM claim_qualifier ORDER BY qualifier_id")).isEqualTo(stored);assertThat(jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id")).isEqualTo(outbox);
    }
    @Test void typedHistoryAndRepinsSurviveReferencedConceptRetirementButScopeAndBoundsRemainStrict(){
        UUID target=concept("Condition value");var first=create();var changedClaim=claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("STRING",null,"Changed Claim"),false,trace);var current=first;
        var values=List.of(edit("STRING","Exact 🧠",2),edit("DATE","2026-10-21",2),edit("BOOLEAN","false",2),edit("CONCEPT",target.toString(),2));
        for(var value:values)current=store.change(space,subject,claim.claimId(),first.qualifierId(),actor,current.version(),value,false,trace);
        concepts.change(space,target,actor,1,null,null,true,trace);var archived=retire(current);
        assertThat(store.retired(space,subject,claim.claimId(),null,1).items()).containsExactly(archived);var revisions=store.history(space,subject,claim.claimId(),first.qualifierId(),Long.MAX_VALUE,10);
        assertThat(revisions).extracting(v->v.value().type()).containsExactly("CONCEPT","CONCEPT","BOOLEAN","DATE","STRING","NUMBER");assertThat(revisions.getFirst().claim().object()).isEqualTo(changedClaim.object());assertThat(revisions.getLast().claim().object()).isEqualTo(claim.object());
        for(int limit:new int[]{0,-1,101,Integer.MAX_VALUE})status(400,()->store.retired(space,subject,claim.claimId(),null,limit));status(400,()->store.retired(space,subject,claim.claimId(),UUID.randomUUID(),20));
        status(404,()->store.retired(ids.next(),subject,claim.claimId(),null,20));status(404,()->store.retired(space,predicate,claim.claimId(),null,20));status(404,()->store.retired(space,subject,ids.next(),null,20));
        status(404,()->store.change(space,subject,claim.claimId(),first.qualifierId(),actor,archived.version(),edit("STRING","Restore",2),false,trace));
        jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",subject);status(404,()->store.retired(space,subject,claim.claimId(),null,20));
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=? AND indexname='claim_qualifier_retired_idx'",String.class,schema)).contains("space_id, subject_concept_id, claim_id, qualifier_id").contains("RETIRED");
    }
}
