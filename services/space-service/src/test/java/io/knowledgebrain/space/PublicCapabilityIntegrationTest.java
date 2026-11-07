package io.knowledgebrain.space;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_SPACE_DATABASE_URL",matches=".+")
class PublicCapabilityIntegrationTest {
    @Test void membershipAndSpecificCurrentCapabilityAreBothRequired(){var ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_SPACE_DATABASE_URL"));ds.setUser("brain_space");ds.setPassword(System.getenv("BRAIN_TEST_SPACE_DATABASE_PASSWORD"));Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();var jdbc=new JdbcTemplate(ds);var owner=new PublicKnowledgeSpaces(ds);UUID space=UUID.fromString("0199e000-0000-7000-8000-000000000002"),id=new UuidV7(Clock.systemUTC()).next();var p=new IdentityPrincipalClient.Principal(id,"REGISTERED",Instant.now().plusSeconds(300));
        assertThatThrownBy(()->owner.authorize(space,p,"CLAIM_EXPERT_VERIFY")).isInstanceOf(ResponseStatusException.class);
        jdbc.update("INSERT INTO space_membership(space_id,principal_id,role,state) VALUES (?,?,'REVIEWER','ACTIVE')",space,id);
        try{owner.authorize(space,p,"CONTRIBUTION_REVIEW");assertThatThrownBy(()->owner.authorize(space,p,"CLAIM_EXPERT_VERIFY")).isInstanceOf(ResponseStatusException.class);jdbc.update("INSERT INTO public_knowledge_capability VALUES (?,?,'CLAIM_EXPERT_VERIFY','Explicit validation credential','ACTIVE',CURRENT_TIMESTAMP)",space,id);owner.authorize(space,p,"CLAIM_EXPERT_VERIFY");assertThatThrownBy(()->owner.authorize(space,p,"MAPPING_OFFICIAL_REVIEW")).isInstanceOf(ResponseStatusException.class);jdbc.update("UPDATE public_knowledge_capability SET state='RETIRED' WHERE space_id=? AND principal_id=?",space,id);assertThatThrownBy(()->owner.authorize(space,p,"CLAIM_EXPERT_VERIFY")).isInstanceOf(ResponseStatusException.class);jdbc.update("UPDATE public_knowledge_capability SET state='ACTIVE' WHERE space_id=? AND principal_id=?",space,id);jdbc.update("UPDATE space_membership SET state='RETIRED' WHERE space_id=? AND principal_id=?",space,id);assertThatThrownBy(()->owner.authorize(space,p,"CLAIM_EXPERT_VERIFY")).isInstanceOf(ResponseStatusException.class);}
        finally{jdbc.update("DELETE FROM public_knowledge_capability WHERE space_id=? AND principal_id=?",space,id);jdbc.update("DELETE FROM space_membership WHERE space_id=? AND principal_id=?",space,id);}
    }
}
