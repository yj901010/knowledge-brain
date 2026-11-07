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
class RetiredClaimStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;ClaimStore claims;ConceptStore concepts;String schema;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,subject,predicate,target;String trace="b".repeat(32);
    @BeforeEach void setup(){
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        schema="retired_claim_test_"+UUID.randomUUID().toString().replace("-","");new JdbcTemplate(source).execute("CREATE SCHEMA "+schema);source.setCurrentSchema(schema);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);claims=new ClaimStore(source,Clock.systemUTC());concepts=new ConceptStore(source,Clock.systemUTC());space=ids.next();actor=ids.next();
        subject=concepts.create(space,actor,"Subject","",trace).conceptId();predicate=concepts.create(space,actor,"Predicate","",trace).conceptId();target=concepts.create(space,actor,"Target","",trace).conceptId();
    }
    @AfterEach void cleanup(){if(jdbc!=null)jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    ClaimStore.Claim create(ClaimStore.ObjectValue object){return claims.create(space,subject,actor,predicate,object,trace);}
    @Test void retiredPagesKeepExactTypesAcrossReferenceRetirementAndFreshStoreWithoutWrites(){
        var expected=new ArrayList<UUID>();
        for(int n=0;n<24;n++){var c=create(new ClaimStore.ObjectValue("NUMBER",null,"9007199254740993."+String.format("%018d",n+1)));claims.change(space,subject,c.claimId(),actor,1,null,null,true,trace);expected.add(c.claimId());}
        var conceptClaim=create(new ClaimStore.ObjectValue("CONCEPT",target));claims.change(space,subject,conceptClaim.claimId(),actor,1,null,null,true,trace);expected.add(conceptClaim.claimId());
        var active=create(new ClaimStore.ObjectValue("STRING",null,"active"));
        var original=claims.history(space,subject,expected.getFirst(),Long.MAX_VALUE,10);
        concepts.change(space,predicate,actor,1,null,null,true,trace);concepts.change(space,target,actor,1,null,null,true,trace);concepts.change(space,subject,actor,1,null,null,true,trace);
        var outbox=jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id");
        var first=claims.retired(space,subject,null,20);assertThat(first.items()).extracting(ClaimStore.Claim::claimId).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.nextAfter()).isEqualTo(expected.get(19));
        claims=new ClaimStore(source,Clock.systemUTC());var second=claims.retired(space,subject,first.nextAfter(),20);assertThat(second.items()).extracting(ClaimStore.Claim::claimId).containsExactlyElementsOf(expected.subList(20,25));assertThat(second.nextAfter()).isNull();
        assertThat(first.items().getFirst().object().value()).isEqualTo("9007199254740993.000000000000000001");assertThat(second.items().getLast().object().conceptId()).isEqualTo(target);assertThat(claims.retired(space,subject,expected.getLast(),1).items()).isEmpty();
        assertThat(claims.history(space,subject,expected.getFirst(),Long.MAX_VALUE,10)).isEqualTo(original);assertThat(jdbc.queryForList("SELECT id,encode(payload,'hex') AS payload FROM outbox_event ORDER BY id")).isEqualTo(outbox);
        assertThat(jdbc.queryForObject("SELECT state FROM claim WHERE claim_id=?",String.class,active.claimId())).isEqualTo("ACTIVE");
        status(404,()->claims.list(space,subject,null,20));status(404,()->claims.get(space,subject,conceptClaim.claimId()));status(404,()->claims.change(space,subject,conceptClaim.claimId(),actor,2,predicate,new ClaimStore.ObjectValue("STRING",null,"restore"),false,trace));
    }
    @Test void retiredDiscoveryRejectsForeignPublicUnknownAndInvalidInputs(){
        var c=create(new ClaimStore.ObjectValue("BOOLEAN",null,"false"));claims.change(space,subject,c.claimId(),actor,1,null,null,true,trace);
        UUID other=ids.next();var foreign=concepts.create(other,actor,"Foreign","",trace);
        status(404,()->claims.retired(other,subject,null,20));status(404,()->claims.retired(space,foreign.conceptId(),null,20));status(404,()->claims.retired(space,ids.next(),null,20));
        var publicId=concepts.create(space,actor,"Public fixture","",trace).conceptId();jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",publicId);status(404,()->claims.retired(space,publicId,null,20));
        status(400,()->claims.retired(space,subject,UUID.randomUUID(),20));status(400,()->claims.retired(space,subject,null,0));status(400,()->claims.retired(space,subject,null,101));
        assertThat(claims.list(space,subject,null,20).items()).isEmpty();assertThat(claims.retired(space,predicate,null,20).items()).isEmpty();assertThat(claims.retired(space,subject,null,1).items()).extracting(ClaimStore.Claim::claimId).containsExactly(c.claimId());
    }
}
