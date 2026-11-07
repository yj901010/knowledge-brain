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
class SourceCitationStoreIntegrationTest {
    PGSimpleDataSource data;
    JdbcTemplate jdbc;
    CitationStore store;
    ConceptStore concepts;
    ClaimStore claims;
    SourceStore sources;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,actor,subject,predicate,object;
    ClaimStore.Claim claim;
    SourceStore.Source source;
    String trace="c".repeat(32);
    record Fixture(UUID subject,UUID predicate,UUID object,ClaimStore.Claim claim,CitationStore.Citation citation) {}

    @BeforeEach void prepare() {
        data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");
        data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(data);
        store=new CitationStore(data,Clock.systemUTC());concepts=new ConceptStore(data,Clock.systemUTC());
        claims=new ClaimStore(data,Clock.systemUTC());sources=new SourceStore(data,Clock.systemUTC());space=ids.next();actor=ids.next();
        subject=concept("Same subject title");predicate=concept("Predicate");object=concept("Object");
        claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",object),trace);
        source=sources.create(space,actor,"Original Source","https://example.org/source","Pinned citation","Manual license note",trace);
    }
    UUID concept(String title) {return concepts.create(space,actor,title,"",trace).conceptId();}
    CitationStore.Citation create() {return store.create(space,subject,claim.claimId(),actor,new CitationStore.Reference(source.sourceId(),source.version(),claim.version()),trace);}
    Fixture fixture(String title) {
        UUID s=concept(title),p=concept("Predicate"),o=concept("Object");
        var c=claims.create(space,s,actor,p,new ClaimStore.ObjectValue("CONCEPT",o),trace);
        var citation=store.create(space,s,c.claimId(),actor,new CitationStore.Reference(source.sourceId(),source.version(),c.version()),trace);
        return new Fixture(s,p,o,c,citation);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }
    List<CitationStore.SourceCitation> all() {return store.bySource(space,source.sourceId(),null,100).items();}
    void retained(Fixture f) {
        assertThat(store.history(space,f.subject(),f.claim().claimId(),f.citation().citationId(),Long.MAX_VALUE,10)).hasSize(1);
        status(404,()->store.get(space,f.subject(),f.claim().claimId(),f.citation().citationId()));
    }

    @Test void twentyTwoClaimsShareOneSourceWithStableTwentyPlusTwoKeysetsAndDuplicateTitles() {
        var expected=new ArrayList<CitationStore.Citation>();expected.add(create());
        for(int i=0;i<21;i++)expected.add(fixture("Same subject title").citation());
        expected.sort(Comparator.comparing(CitationStore.Citation::citationId));
        var first=store.bySource(space,source.sourceId(),null,20);assertThat(first.items()).hasSize(20);
        assertThat(first.items()).extracting(CitationStore.SourceCitation::citationId).containsExactlyElementsOf(expected.subList(0,20).stream().map(CitationStore.Citation::citationId).toList());
        assertThat(first.nextAfter()).isEqualTo(first.items().getLast().citationId());
        var second=store.bySource(space,source.sourceId(),first.nextAfter(),20);assertThat(second.items()).hasSize(2);assertThat(second.nextAfter()).isNull();
        assertThat(second.items()).extracting(CitationStore.SourceCitation::citationId).containsExactlyElementsOf(expected.subList(20,22).stream().map(CitationStore.Citation::citationId).toList());
        assertThat(store.bySource(space,source.sourceId(),expected.getLast().citationId(),20).items()).isEmpty();
        assertThat(all()).extracting(CitationStore.SourceCitation::citationId).doesNotHaveDuplicates();
        for(var item:all()) {
            var pinned=expected.stream().filter(c->c.citationId().equals(item.citationId())).findFirst().orElseThrow();
            assertThat(item.subjectTitle()).isEqualTo("Same subject title");assertThat(item.subjectConceptId()).isEqualTo(pinned.subjectConceptId());
            assertThat(item.spaceId()).isEqualTo(space);assertThat(item.claimId()).isEqualTo(pinned.claimId());assertThat(item.claim()).isEqualTo(pinned.claim());
            assertThat(item.source()).isEqualTo(new CitationStore.SourcePin(source.sourceId(),pinned.source().revisionId(),1));
            assertThat(item.version()).isEqualTo(pinned.version());assertThat(item.currentClaimVersion()).isEqualTo(1);
            assertThat(item.visibility()).isEqualTo("PRIVATE");assertThat(item.state()).isEqualTo("ACTIVE");
            assertThat(item.createdAt()).isEqualTo(pinned.createdAt());assertThat(item.updatedAt()).isEqualTo(pinned.updatedAt());
        }
    }

    @Test void currentDiscoveryTitleAndClaimVersionAreSeparateFromImmutablePinsAcrossParentEdits() {
        var entry=create();var initial=all().getFirst();
        concepts.change(space,subject,actor,1,"😀".repeat(200),"Changed subject description",false,trace);
        var newestSource=sources.change(space,source.sourceId(),actor,1,"Current source",null,"Edited bibliography","Edited license",false,trace);
        UUID newestPredicate=concept("New predicate"),newestObject=concept("New object");
        var newestClaim=claims.change(space,subject,claim.claimId(),actor,1,newestPredicate,new ClaimStore.ObjectValue("CONCEPT",newestObject),false,trace);
        store=new CitationStore(data,Clock.systemUTC());var discovered=all().getFirst();
        assertThat(discovered.subjectTitle()).isEqualTo("😀".repeat(200));assertThat(discovered.subjectTitle().codePointCount(0,discovered.subjectTitle().length())).isEqualTo(200);
        assertThat(discovered.currentClaimVersion()).isEqualTo(newestClaim.version());assertThat(discovered.claim()).isEqualTo(initial.claim());
        assertThat(discovered.claim().version()).isEqualTo(1);assertThat(discovered.claim().predicateConceptId()).isEqualTo(predicate);assertThat(discovered.claim().object().conceptId()).isEqualTo(object);
        assertThat(discovered.source()).isEqualTo(initial.source());assertThat(discovered.source().version()).isEqualTo(1);assertThat(newestSource.version()).isEqualTo(2);
        assertThat(discovered.version()).isEqualTo(initial.version());assertThat(discovered.updatedAt()).isEqualTo(initial.updatedAt());
        assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);
        var repinned=store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,new CitationStore.Reference(source.sourceId(),2,2),false,trace);
        var after=all().getFirst();assertThat(after.version()).isEqualTo(2);assertThat(after.currentClaimVersion()).isEqualTo(2);
        assertThat(after.claim()).isEqualTo(repinned.claim());assertThat(after.source().revisionId()).isEqualTo(repinned.source().revisionId());assertThat(after.source().version()).isEqualTo(2);
    }

    @Test void sourceRetirementKeepsActiveDiscoveryAndPinnedDetailWhileKnownUnusedSourceReturnsEmpty() {
        var entry=create();var initial=all();sources.change(space,source.sourceId(),actor,1,null,null,null,null,true,trace);
        status(404,()->sources.get(space,source.sourceId()));assertThat(all()).isEqualTo(initial);
        assertThat(store.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);
        assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10)).hasSize(1);
        var unused=sources.create(space,actor,"Unused",null,"No relations","",trace);
        var empty=store.bySource(space,unused.sourceId(),null,20);assertThat(empty.items()).isEmpty();assertThat(empty.nextAfter()).isNull();
        sources.change(space,unused.sourceId(),actor,1,null,null,null,null,true,trace);
        assertThat(store.bySource(space,unused.sourceId(),null,20)).isEqualTo(empty);
    }

    @Test void unlinkHidesDiscoveryWithoutDestroyingKnownHistoryAndRelinkGetsIndependentIdentity() {
        var entry=create();store.change(space,subject,claim.claimId(),entry.citationId(),actor,1,null,true,trace);
        assertThat(all()).isEmpty();status(404,()->store.get(space,subject,claim.claimId(),entry.citationId()));
        var history=store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10);
        assertThat(history).extracting(CitationStore.Revision::version).containsExactly(2L,1L);assertThat(history.getLast().claim()).isEqualTo(entry.claim());
        assertThat(history.getLast().source()).isEqualTo(entry.source());assertThat(history.getFirst().state()).isEqualTo("RETIRED");
        var replacement=create();assertThat(replacement.citationId()).isNotEqualTo(entry.citationId());
        assertThat(all()).extracting(CitationStore.SourceCitation::citationId).containsExactly(replacement.citationId());
        assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),2,1)).containsExactly(history.getLast());
    }

    @Test void retiredCurrentClaimIsHiddenButKnownCitationHistorySurvives() {
        var entry=create();claims.change(space,subject,claim.claimId(),actor,1,null,null,true,trace);
        assertThat(all()).isEmpty();status(404,()->store.get(space,subject,claim.claimId(),entry.citationId()));
        assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10).getFirst().claim()).isEqualTo(entry.claim());
    }

    @Test void retirementOfEachCurrentReferencedConceptHidesDiscoveryButRetainsKnownHistory() {
        for(int role=0;role<3;role++) {
            var f=fixture("Same subject title");UUID target=List.of(f.subject(),f.predicate(),f.object()).get(role);
            concepts.change(space,target,actor,1,null,null,true,trace);
            assertThat(all()).extracting(CitationStore.SourceCitation::citationId).doesNotContain(f.citation().citationId());retained(f);
        }
    }

    @Test void publicVisibilityOfEachCurrentReferencedConceptCannotExposePrivateCitations() {
        for(int role=0;role<3;role++) {
            var f=fixture("Same subject title");UUID target=List.of(f.subject(),f.predicate(),f.object()).get(role);
            jdbc.update("UPDATE concept SET visibility='PUBLIC' WHERE concept_id=?",target);
            assertThat(all()).extracting(CitationStore.SourceCitation::citationId).doesNotContain(f.citation().citationId());retained(f);
        }
    }

    @Test void effectiveFilteringUsesCurrentTripleRatherThanPinnedConceptLifecycle() {
        var entry=create();UUID newPredicate=concept("Current predicate"),newObject=concept("Current object");
        claims.change(space,subject,claim.claimId(),actor,1,newPredicate,new ClaimStore.ObjectValue("CONCEPT",newObject),false,trace);
        concepts.change(space,predicate,actor,1,null,null,true,trace);concepts.change(space,object,actor,1,null,null,true,trace);
        assertThat(all()).hasSize(1);assertThat(all().getFirst().claim()).isEqualTo(entry.claim());assertThat(all().getFirst().currentClaimVersion()).isEqualTo(2);
        concepts.change(space,newPredicate,actor,1,null,null,true,trace);assertThat(all()).isEmpty();
        assertThat(store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10).getFirst().claim()).isEqualTo(entry.claim());
    }

    @Test void sourceIdentityIsSpaceScopedAndForeignCursorNeverChangesItsScope() {
        var entry=create();UUID other=ids.next();
        var foreignSource=sources.create(other,actor,"Foreign source",null,"Private bibliography","",trace);
        var foreignConcept=concepts.create(other,actor,"Foreign subject","",trace);
        var foreignClaim=claims.create(other,foreignConcept.conceptId(),actor,foreignConcept.conceptId(),new ClaimStore.ObjectValue("CONCEPT",foreignConcept.conceptId()),trace);
        var foreignCitation=store.create(other,foreignConcept.conceptId(),foreignClaim.claimId(),actor,new CitationStore.Reference(foreignSource.sourceId(),1,1),trace);
        status(404,()->store.bySource(space,foreignSource.sourceId(),null,20));status(404,()->store.bySource(other,source.sourceId(),null,20));
        status(404,()->store.bySource(space,ids.next(),null,20));status(404,()->store.bySource(ids.next(),source.sourceId(),null,20));
        assertThat(store.bySource(other,foreignSource.sourceId(),null,20).items()).extracting(CitationStore.SourceCitation::citationId).containsExactly(foreignCitation.citationId());
        assertThat(store.bySource(space,source.sourceId(),foreignCitation.citationId(),20).items()).allSatisfy(item->{
            assertThat(item.spaceId()).isEqualTo(space);assertThat(item.source().sourceId()).isEqualTo(source.sourceId());assertThat(item.citationId()).isEqualTo(entry.citationId());
        });
        assertThat(all()).extracting(CitationStore.SourceCitation::citationId).containsExactly(entry.citationId());
    }

    @Test void readsLeaveEveryCanonicalRevisionAndOutboxRowByteForByteUnchanged() {
        var entry=create();var alias=new AliasStore(data,Clock.systemUTC()).create(space,subject,actor,"Sibling alias",trace);
        var another=fixture("Same subject title");var before=snapshot();
        for(int i=0;i<3;i++) {
            var first=store.bySource(space,source.sourceId(),null,1);assertThat(first.items()).hasSize(1);assertThat(first.nextAfter()).isNotNull();
            assertThat(store.bySource(space,source.sourceId(),first.nextAfter(),100).items()).hasSize(1);
            store.get(space,subject,claim.claimId(),entry.citationId());store.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10);
        }
        status(404,()->store.bySource(space,ids.next(),null,20));assertThat(snapshot()).isEqualTo(before);
        assertThat(claims.get(space,subject,claim.claimId()).verificationState()).isEqualTo("DRAFT");
        assertThat(new AliasStore(data,Clock.systemUTC()).get(space,subject,alias.aliasId())).isEqualTo(alias);
        assertThat(store.history(space,another.subject(),another.claim().claimId(),another.citation().citationId(),Long.MAX_VALUE,10)).hasSize(1);
    }

    @Test void v7IndexIsPartialSpaceSourceCitationKeysetAndInvalidInputsNeverTouchRows() {
        String definition=jdbc.queryForObject("SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indexrelid='claim_citation_source_active_idx'::regclass AND indrelid='claim_citation'::regclass",String.class);
        assertThat(definition).contains("(space_id, source_id, citation_id)","WHERE (state = 'ACTIVE'::text)");
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='7'",Boolean.class)).isTrue();
        var before=snapshot();status(400,()->store.bySource(UUID.randomUUID(),source.sourceId(),null,20));
        status(400,()->store.bySource(space,UUID.randomUUID(),null,20));status(400,()->store.bySource(space,source.sourceId(),UUID.randomUUID(),20));
        status(400,()->store.bySource(space,source.sourceId(),null,0));status(400,()->store.bySource(space,source.sourceId(),null,101));
        assertThat(snapshot()).isEqualTo(before);
    }

    Map<String,List<String>> snapshot() {
        var result=new LinkedHashMap<String,List<String>>();
        for(String table:List.of("concept","alias","claim","knowledge_source","claim_citation"))
            result.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t WHERE space_id=? ORDER BY to_jsonb(t)::text",String.class,space));
        var owners=Map.of("concept_revision","concept","alias_revision","alias","claim_revision","claim","source_revision","knowledge_source","citation_revision","claim_citation");
        var keys=Map.of("concept_revision","concept_id","alias_revision","alias_id","claim_revision","claim_id","source_revision","source_id","citation_revision","citation_id");
        for(String table:owners.keySet()) {
            String key=keys.get(table);result.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t JOIN "+owners.get(table)+" o ON o."+key+"=t."+key+" WHERE o.space_id=? ORDER BY to_jsonb(t)::text",String.class,space));
        }
        result.put("outbox_event",jdbc.queryForList("SELECT to_jsonb(t)::text FROM outbox_event t WHERE aggregateid IN (SELECT concept_id FROM concept WHERE space_id=? UNION SELECT alias_id FROM alias WHERE space_id=? UNION SELECT claim_id FROM claim WHERE space_id=? UNION SELECT source_id FROM knowledge_source WHERE space_id=? UNION SELECT citation_id FROM claim_citation WHERE space_id=?) ORDER BY to_jsonb(t)::text",String.class,space,space,space,space,space));
        return result;
    }
}
