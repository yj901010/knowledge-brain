package io.knowledgebrain.content;

import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class ConceptNotesIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;
    UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="c".repeat(32);
    UUID space,other,actor,concept;
    @BeforeEach void prepare(){
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,Clock.systemUTC());
        space=ids.next();other=ids.next();actor=ids.next();concept=ids.next();
    }
    NoteStore.Note linked(UUID targetSpace,String title,UUID target){var n=store.create(targetSpace,actor,title,"Private body",trace);store.concepts(targetSpace,n.noteId(),actor,0,List.of(target),trace);return n;}
    @Test void actualUuidCursorPagingKeepsDuplicateTitlesSeparateAndNeverIncludesForeignNotes(){
        var expected=new ArrayList<UUID>();for(int i=0;i<21;i++)expected.add(linked(space,"Same title",concept).noteId());expected.sort(Comparator.comparing(UUID::toString));
        linked(other,"Foreign secret",concept);store.create(space,actor,"Unlinked","",trace);linked(space,"Different target",ids.next());
        var first=store.notesByConcept(space,concept,null,20);assertThat(first.items()).hasSize(20);assertThat(first.items()).extracting(NoteConceptStore.LinkedNote::noteId).containsExactlyElementsOf(expected.subList(0,20));assertThat(first.items()).extracting(NoteConceptStore.LinkedNote::title).containsOnly("Same title");assertThat(first.nextAfter()).isEqualTo(expected.get(19));
        var last=store.notesByConcept(space,concept,first.nextAfter(),20);assertThat(last.items()).extracting(NoteConceptStore.LinkedNote::noteId).containsExactly(expected.get(20));assertThat(last.nextAfter()).isNull();
        assertThat(store.notesByConcept(space,concept,expected.get(20),20).items()).isEmpty();
        assertThat(store.notesByConcept(other,concept,null,20).items()).extracting(NoteConceptStore.LinkedNote::title).containsExactly("Foreign secret");
        assertThat(store.notesByConcept(space,ids.next(),null,20).items()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND indexname='note_concept_space_target_note_idx'",Long.class)).isEqualTo(1);
    }
    @Test void canonicalRenameReferenceRemovalAndNoteRetirementAppearImmediatelyWithoutProjectionLag(){
        var renamed=linked(space,"Old title",concept);var removed=linked(space,"Removed reference",concept);var retired=linked(space,"Retired Note",concept);
        store.change(space,renamed.noteId(),actor,1,"New title","Changed body",false,trace);
        store.concepts(space,removed.noteId(),actor,1,List.of(),trace);
        store.change(space,retired.noteId(),actor,1,null,null,true,trace);
        assertThat(store.notesByConcept(space,concept,null,20).items()).containsExactly(new NoteConceptStore.LinkedNote(renamed.noteId(),"New title"));
        store=new NoteStore(source,Clock.systemUTC());assertThat(store.notesByConcept(space,concept,null,20).items()).containsExactly(new NoteConceptStore.LinkedNote(renamed.noteId(),"New title"));
        assertThat(store.conceptHistory(space,retired.noteId(),Long.MAX_VALUE,10)).hasSize(2);
    }
    @Test void unknownTargetIsEmptyAndRetainedExternalIdsNeedNoKnowledgeAvailabilityOrTargetLifecycle(){
        UUID retainedExternalTarget=ids.next();var n=linked(space,"Retained target reference",retainedExternalTarget);
        // Content has no canonical Concept table or target lifecycle lookup. Retained references
        // are readable even when Knowledge no longer exposes an active target definition.
        assertThat(store.notesByConcept(space,retainedExternalTarget,null,20).items()).containsExactly(new NoteConceptStore.LinkedNote(n.noteId(),n.title()));
        UUID foreignOnly=ids.next();linked(other,"Foreign target secret",foreignOnly);
        assertThat(store.notesByConcept(space,foreignOnly,null,20).items()).isEmpty();assertThat(store.notesByConcept(space,ids.next(),null,20).items()).isEmpty();
    }
    @Test void repeatedReverseReadsNeverWriteNotesReferencesRevisionsOrOutbox(){
        var n=linked(space,"Unchanged",concept);var counts=counts();var references=store.concepts(space,n.noteId());
        for(int i=0;i<3;i++)assertThat(store.notesByConcept(space,concept,null,20).items()).hasSize(1);
        assertThat(counts()).isEqualTo(counts);assertThat(store.get(space,n.noteId()).version()).isEqualTo(1);assertThat(store.concepts(space,n.noteId())).isEqualTo(references);
    }
    List<Long> counts(){return List.of(
        jdbc.queryForObject("SELECT count(*) FROM note_revision r JOIN note n ON n.note_id=r.note_id WHERE n.space_id=?",Long.class,space),
        jdbc.queryForObject("SELECT count(*) FROM note_concept_link_revision WHERE space_id=?",Long.class,space),
        jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN(SELECT note_id FROM note WHERE space_id=?)",Long.class,space));}
}
