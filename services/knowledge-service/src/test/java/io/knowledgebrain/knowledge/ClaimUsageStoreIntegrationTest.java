package io.knowledgebrain.knowledge;

import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_DATABASE_URL",matches=".+")
class ClaimUsageStoreIntegrationTest {
    PGSimpleDataSource data;JdbcTemplate jdbc;ClaimStore store;ConceptStore concepts;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,target,predicate;String trace="c".repeat(32);
    @BeforeEach void prepare(){
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);store=new ClaimStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());space=ids.next();actor=ids.next();target=concept("Target");predicate=concept("Predicate");
    }
    UUID concept(String title){return concepts.create(space,actor,title,"",trace).conceptId();}
    ClaimStore.Claim create(UUID subject,UUID p,UUID o){return store.create(space,subject,actor,p,new ClaimStore.ObjectValue("CONCEPT",o),trace);}
    ClaimStore.Claim incoming(String title){return create(concept(title),predicate,target);}
    List<ClaimStore.Usage> usages(UUID id,ClaimStore.UsageRole role){return store.usages(space,id,role,null,100).items();}
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(error->assertThat(((ResponseStatusException)error).getStatusCode().value()).isEqualTo(expected));}

    @Test void objectPagesHaveTwentyPlusTwoStableClaimIdsAndCurrentTitles(){
        var claims=new ArrayList<ClaimStore.Claim>();for(int i=0;i<22;i++)claims.add(incoming("Same title"));claims.sort(Comparator.comparing(ClaimStore.Claim::claimId));
        var first=store.usages(space,target,ClaimStore.UsageRole.OBJECT,null,20);assertThat(first.items()).hasSize(20);assertThat(first.nextAfter()).isEqualTo(claims.get(19).claimId());
        assertThat(first.items()).extracting(row->row.claim().claimId()).containsExactlyElementsOf(claims.subList(0,20).stream().map(ClaimStore.Claim::claimId).toList());
        var second=store.usages(space,target,ClaimStore.UsageRole.OBJECT,first.nextAfter(),20);assertThat(second.items()).hasSize(2);assertThat(second.nextAfter()).isNull();
        assertThat(second.items()).extracting(row->row.claim().claimId()).containsExactlyElementsOf(claims.subList(20,22).stream().map(ClaimStore.Claim::claimId).toList());
        for(var row:usages(target,ClaimStore.UsageRole.OBJECT)){assertThat(row.subjectTitle()).isEqualTo("Same title");assertThat(row.predicateTitle()).isEqualTo("Predicate");assertThat(row.objectTitle()).isEqualTo("Target");assertThat(row.claim().spaceId()).isEqualTo(space);assertThat(row.claim().verificationState()).isEqualTo("DRAFT");}
    }
    @Test void roleScopesAreIndependentAndMultiplePositionsProduceOneRowPerRole(){
        var objectOnly=incoming("Object only");var predicateOnly=create(concept("Predicate only"),target,predicate);var self=create(target,target,target);
        assertThat(usages(target,ClaimStore.UsageRole.OBJECT)).extracting(row->row.claim().claimId()).containsExactly(objectOnly.claimId(),self.claimId());
        assertThat(usages(target,ClaimStore.UsageRole.PREDICATE)).extracting(row->row.claim().claimId()).containsExactly(predicateOnly.claimId(),self.claimId());
        assertThat(store.usages(space,target,ClaimStore.UsageRole.OBJECT,self.claimId(),20).items()).isEmpty();
    }
    @Test void currentTitlesAndTripleChangesDoNotRewriteKnownClaimHistory(){
        var claim=incoming("Initial subject");var before=store.history(space,claim.subjectConceptId(),claim.claimId(),Long.MAX_VALUE,10);
        concepts.change(space,claim.subjectConceptId(),actor,1,"😀".repeat(200),"Changed",false,trace);concepts.change(space,predicate,actor,1,"Current predicate","",false,trace);concepts.change(space,target,actor,1,"Current target","",false,trace);
        var row=usages(target,ClaimStore.UsageRole.OBJECT).getFirst();assertThat(row.claim()).isEqualTo(claim);assertThat(row.subjectTitle()).isEqualTo("😀".repeat(200));assertThat(row.predicateTitle()).isEqualTo("Current predicate");assertThat(row.objectTitle()).isEqualTo("Current target");assertThat(store.history(space,claim.subjectConceptId(),claim.claimId(),Long.MAX_VALUE,10)).isEqualTo(before);
        store.change(space,claim.subjectConceptId(),claim.claimId(),actor,1,target,new ClaimStore.ObjectValue("CONCEPT",predicate),false,trace);
        assertThat(usages(target,ClaimStore.UsageRole.OBJECT)).isEmpty();assertThat(usages(target,ClaimStore.UsageRole.PREDICATE).getFirst().claim().version()).isEqualTo(2);
        assertThat(store.history(space,claim.subjectConceptId(),claim.claimId(),Long.MAX_VALUE,10).getLast()).isEqualTo(before.getFirst());
    }
    @Test void retiringEachCurrentConceptHidesUsageWithoutChangingKnownHistory(){
        for(int role=0;role<3;role++){
            UUID s=concept("Subject"),p=concept("Predicate"),o=concept("Object");var claim=create(s,p,o);var history=store.history(space,s,claim.claimId(),Long.MAX_VALUE,10);
            concepts.change(space,List.of(s,p,o).get(role),actor,1,null,null,true,trace);
            assertThat(usages(o,ClaimStore.UsageRole.OBJECT)).isEmpty();assertThat(usages(p,ClaimStore.UsageRole.PREDICATE)).isEmpty();assertThat(store.history(space,s,claim.claimId(),Long.MAX_VALUE,10)).isEqualTo(history);status(404,()->store.get(space,s,claim.claimId()));
        }
    }
    @Test void retirementAndChangedTargetsUseExclusiveCursorsWithoutDiscoveringOldTriples(){
        var first=incoming("First");var second=incoming("Second");var third=incoming("Third");
        store.change(space,first.subjectConceptId(),first.claimId(),actor,1,null,null,true,trace);
        var page=store.usages(space,target,ClaimStore.UsageRole.OBJECT,first.claimId(),1);assertThat(page.items().getFirst().claim().claimId()).isEqualTo(second.claimId());assertThat(page.nextAfter()).isEqualTo(second.claimId());
        store.change(space,second.subjectConceptId(),second.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",predicate),false,trace);
        assertThat(store.usages(space,target,ClaimStore.UsageRole.OBJECT,page.nextAfter(),20).items()).extracting(row->row.claim().claimId()).containsExactly(third.claimId());
        assertThat(store.history(space,first.subjectConceptId(),first.claimId(),Long.MAX_VALUE,10)).extracting(ClaimStore.Revision::state).containsExactly("RETIRED","ACTIVE");
    }
    @Test void knownEmptyAndRetiredConceptsAreReadableButUnknownForeignAndPublicAreNot(){
        assertThat(usages(target,ClaimStore.UsageRole.OBJECT)).isEmpty();concepts.change(space,target,actor,1,null,null,true,trace);assertThat(usages(target,ClaimStore.UsageRole.OBJECT)).isEmpty();
        status(404,()->store.usages(space,ids.next(),ClaimStore.UsageRole.OBJECT,null,20));status(404,()->store.usages(ids.next(),predicate,ClaimStore.UsageRole.OBJECT,null,20));
        UUID other=ids.next();var foreign=concepts.create(other,actor,"Foreign","",trace);status(404,()->store.usages(space,foreign.conceptId(),ClaimStore.UsageRole.PREDICATE,null,20));
        jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",predicate);status(404,()->store.usages(space,predicate,ClaimStore.UsageRole.PREDICATE,null,20));
    }
    @Test void publicReferencedConceptsCannotExposePrivateRelationships(){
        for(int role=0;role<3;role++){
            UUID s=concept("Subject"),p=concept("Predicate"),o=concept("Object");var claim=create(s,p,o);var history=store.history(space,s,claim.claimId(),Long.MAX_VALUE,10);
            jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",List.of(s,p,o).get(role));
            if(role==2)status(404,()->store.usages(space,o,ClaimStore.UsageRole.OBJECT,null,20));else assertThat(usages(o,ClaimStore.UsageRole.OBJECT)).isEmpty();
            assertThat(store.history(space,s,claim.claimId(),Long.MAX_VALUE,10)).isEqualTo(history);
        }
    }
    @Test void readsNeverMutateCanonicalRevisionsCitationsOrOutboxAndForeignCursorStaysScoped(){
        var claim=incoming("Subject");new AliasStore(data,Clock.systemUTC()).create(space,target,actor,"Target alias",trace);
        var source=new SourceStore(data,Clock.systemUTC()).create(space,actor,"Source",null,"Citation","",trace);
        new CitationStore(data,Clock.systemUTC()).create(space,claim.subjectConceptId(),claim.claimId(),actor,new CitationStore.Reference(source.sourceId(),1,1),trace);
        var before=snapshot();for(var role:ClaimStore.UsageRole.values()){store.usages(space,target,role,null,20);store.usages(space,target,role,ids.next(),100);}
        store.get(space,claim.subjectConceptId(),claim.claimId());store.history(space,claim.subjectConceptId(),claim.claimId(),Long.MAX_VALUE,10);status(404,()->store.usages(space,ids.next(),ClaimStore.UsageRole.OBJECT,null,20));assertThat(snapshot()).isEqualTo(before);
    }
    @Test void v8IndexesAndValidationKeepReadScopeBounded(){
        for(var entry:Map.of("claim_object_active_idx","object_concept_id","claim_predicate_active_idx","predicate_concept_id").entrySet()){
            String definition=jdbc.queryForObject("SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indexrelid=?::regclass AND indrelid='claim'::regclass",String.class,entry.getKey());assertThat(definition).contains("(space_id, "+entry.getValue()+", claim_id)","WHERE (state = 'ACTIVE'::text)");
        }assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='8'",Boolean.class)).isTrue();
        var before=snapshot();status(400,()->store.usages(UUID.randomUUID(),target,ClaimStore.UsageRole.OBJECT,null,20));status(400,()->store.usages(space,UUID.randomUUID(),ClaimStore.UsageRole.OBJECT,null,20));status(400,()->store.usages(space,target,null,null,20));status(400,()->store.usages(space,target,ClaimStore.UsageRole.OBJECT,UUID.randomUUID(),20));
        status(400,()->store.usages(space,target,ClaimStore.UsageRole.OBJECT,null,0));status(400,()->store.usages(space,target,ClaimStore.UsageRole.OBJECT,null,101));assertThat(snapshot()).isEqualTo(before);
    }
    Map<String,List<String>> snapshot(){
        var result=new TreeMap<String,List<String>>();for(String table:List.of("concept","alias","claim","knowledge_source","claim_citation"))result.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t WHERE space_id=? ORDER BY to_jsonb(t)::text",String.class,space));
        var owners=Map.of("concept_revision","concept","alias_revision","alias","claim_revision","claim","source_revision","knowledge_source","citation_revision","claim_citation");var keys=Map.of("concept_revision","concept_id","alias_revision","alias_id","claim_revision","claim_id","source_revision","source_id","citation_revision","citation_id");
        for(String table:owners.keySet()){String key=keys.get(table);result.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t JOIN "+owners.get(table)+" o ON o."+key+"=t."+key+" WHERE o.space_id=? ORDER BY to_jsonb(t)::text",String.class,space));}
        result.put("outbox",jdbc.queryForList("SELECT to_jsonb(t)::text FROM outbox_event t WHERE aggregateid IN (SELECT concept_id FROM concept WHERE space_id=? UNION SELECT alias_id FROM alias WHERE space_id=? UNION SELECT claim_id FROM claim WHERE space_id=? UNION SELECT source_id FROM knowledge_source WHERE space_id=? UNION SELECT citation_id FROM claim_citation WHERE space_id=?) ORDER BY to_jsonb(t)::text",String.class,space,space,space,space,space));return result;
    }
}
