package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import io.knowledgebrain.contracts.content.links.v1.*;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class NoteLinksIntegrationTest {
    PGSimpleDataSource db;JdbcTemplate jdbc;NoteStore store;UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor;String trace="a".repeat(32);
    @BeforeEach void prepare() {
        db=new PGSimpleDataSource();db.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));db.setUser("brain_content");db.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(db).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(db);store=new NoteStore(db,Clock.systemUTC());space=ids.next();actor=ids.next();
    }
    NoteStore.Note create(String title,String markdown) {return store.create(space,actor,title,markdown,trace);}
    NoteLinkStore.View link(UUID source) {return store.outgoing(space,source,null,20).items().getFirst();}
    @Test void renameKeepsStableTargetAndLinkIdsAcrossSourceEditsAndNameReuse() {
        var target=create("Original","");var source=create("Source","[[Original#section|alias]]");UUID linkId=link(source.noteId()).linkId();
        store.change(space,target.noteId(),actor,1,"Renamed","",false,trace);assertThat(link(source.noteId()).targetNoteId()).isEqualTo(target.noteId());assertThat(link(source.noteId()).targetTitle()).isEqualTo("Renamed");
        create("Original","");store.change(space,source.noteId(),actor,1,"Source","prefix [[Original#new|new alias]]",false,trace);
        assertThat(link(source.noteId()).linkId()).isEqualTo(linkId);assertThat(link(source.noteId()).targetNoteId()).isEqualTo(target.noteId());assertThat(link(source.noteId()).fragment()).isEqualTo("new");
        assertThat(store.backlinks(space,target.noteId(),null,20).items()).extracting(NoteLinkStore.Backlink::sourceNoteId).containsExactly(source.noteId());
    }
    @Test void unresolvedCreationAndRenameResolveWithoutChangingSourceNoteVersion() throws Exception {
        var source=create("Source","[[Future]]");assertThat(link(source.noteId()).status()).isEqualTo("UNRESOLVED");var target=create("Different","");store.change(space,target.noteId(),actor,1,"Future","",false,trace);
        assertThat(link(source.noteId()).targetNoteId()).isEqualTo(target.noteId());assertThat(store.get(space,source.noteId()).version()).isEqualTo(1);assertThat(store.outgoing(space,source.noteId(),null,20).linkVersion()).isEqualTo(2);
        var payload=NoteLinksChangedPayloadV1.parseFrom(jdbc.queryForObject("SELECT payload FROM note_link_revision WHERE source_note_id=? AND link_version=2",byte[].class,source.noteId()));assertThat(payload.getNoteVersion()).isEqualTo(1);assertThat(payload.getLinks(0).getTargetNoteId()).isEqualTo(target.noteId().toString());
    }
    @Test void duplicateTitlesAreAmbiguousUntilOneCandidateRemainsAndExplicitIdsDisambiguate() {
        var first=create("Same","");var second=create("Same","");var source=create("Source","[[Same]] [[note:"+second.noteId()+"|chosen]]");
        assertThat(link(source.noteId()).status()).isEqualTo("AMBIGUOUS");assertThat(store.outgoing(space,source.noteId(),null,20).items().getLast().targetNoteId()).isEqualTo(second.noteId());
        store.change(space,first.noteId(),actor,1,null,null,true,trace);assertThat(link(source.noteId()).targetNoteId()).isEqualTo(second.noteId());
    }
    @Test void crossSpaceIdAndTitleNeverResolveAndCompositeForeignKeysEnforceScope() {
        UUID other=ids.next();var secret=store.create(other,actor,"Secret","",trace);var source=create("Source","[[Secret]] [[note:"+secret.noteId()+"]]");
        assertThat(store.outgoing(space,source.noteId(),null,20).items()).allMatch(l->l.status().equals("UNRESOLVED") && l.targetTitle()==null && l.targetNoteId()==null);
        assertThatThrownBy(()->store.backlinks(space,secret.noteId(),null,20)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE note_link SET target_note_id=?,status='RESOLVED' WHERE source_note_id=?",secret.noteId(),source.noteId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void targetRetirementDoesNotRetargetNameReuseAndSourceUnlinkRetirementRemoveBacklinks() {
        var target=create("Target","");var source=create("Source","[[Target]] [[Target|again]]");assertThat(store.backlinks(space,target.noteId(),null,20).items().getFirst().linkCount()).isEqualTo(2);
        store.change(space,target.noteId(),actor,1,null,null,true,trace);var replacement=create("Target","");assertThat(link(source.noteId()).status()).isEqualTo("TARGET_RETIRED");assertThat(link(source.noteId()).targetNoteId()).isEqualTo(target.noteId());assertThat(store.backlinks(space,replacement.noteId(),null,20).items()).isEmpty();
        store.change(space,source.noteId(),actor,1,"Source","",false,trace);assertThat(store.outgoing(space,source.noteId(),null,20).items()).isEmpty();
        var active=create("Active","");store.change(space,source.noteId(),actor,2,"Source","[[Active]]",false,trace);store.change(space,source.noteId(),actor,3,null,null,true,trace);assertThat(store.backlinks(space,active.noteId(),null,20).items()).isEmpty();assertThatThrownBy(()->store.outgoing(space,source.noteId(),null,20)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void linkOutboxFailureRollsBackTargetCreationResolutionAndNoteRevision() {
        var source=create("Source","[[Target]]");long version=store.outgoing(space,source.noteId(),null,20).linkVersion();
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_reject_link_trace CHECK(type<>'NoteLinksChanged' OR trace_id<>'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb') NOT VALID");
        try {assertThatThrownBy(()->store.create(space,actor,"Target","","b".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(link(source.noteId()).status()).isEqualTo("UNRESOLVED");assertThat(store.outgoing(space,source.noteId(),null,20).linkVersion()).isEqualTo(version);
            assertThatThrownBy(()->store.change(space,source.noteId(),actor,1,"Source","changed",false,"b".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(store.get(space,source.noteId()).version()).isEqualTo(1);assertThat(store.history(space,source.noteId(),Long.MAX_VALUE,10)).hasSize(1);
        }finally {jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_reject_link_trace");}
    }
    @Test void linksAndBacklinksPaginationAndRestartDoNotCreateDuplicateSnapshots() {
        var target=create("Target","");var source=create("Source","[[Target]] [[Target]] [[Missing]]");var second=create("Second","[[Target]]");
        var page=store.outgoing(space,source.noteId(),null,1);assertThat(page.nextAfter()).isEqualTo(0);assertThat(store.outgoing(space,source.noteId(),page.nextAfter(),1).items().getFirst().ordinal()).isEqualTo(1);
        var back=store.backlinks(space,target.noteId(),null,1);assertThat(back.nextAfter()).isNotNull();assertThat(store.backlinks(space,target.noteId(),back.nextAfter(),1).items()).hasSize(1);
        UUID link=link(source.noteId()).linkId();long count=jdbc.queryForObject("SELECT count(*) FROM note_link_revision WHERE source_note_id=?",Long.class,source.noteId());store=new NoteStore(db,Clock.systemUTC());assertThat(link(source.noteId()).linkId()).isEqualTo(link);assertThat(jdbc.queryForObject("SELECT count(*) FROM note_link_revision WHERE source_note_id=?",Long.class,source.noteId())).isEqualTo(count);
    }
    @Test void concurrentOppositeLinksUseSpaceSerializationWithoutDeadlock() throws Exception {
        var a=create("A","");var b=create("B","");try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=workers.submit(()->store.change(space,a.noteId(),actor,1,"A","[[B]]",false,trace));var two=workers.submit(()->store.change(space,b.noteId(),actor,1,"B","[[A]]",false,trace));assertThat(one.get(20,TimeUnit.SECONDS).version()).isEqualTo(2);assertThat(two.get(20,TimeUnit.SECONDS).version()).isEqualTo(2);
        }assertThat(link(a.noteId()).targetNoteId()).isEqualTo(b.noteId());assertThat(link(b.noteId()).targetNoteId()).isEqualTo(a.noteId());
    }
    @Test void legacyDirtyBackfillIsIdempotentAndExcessiveLinksRollBackNewNote() {
        var target=create("Target","");var source=create("Source","");jdbc.update("UPDATE note SET markdown='[[Target]]' WHERE note_id=?",source.noteId());store=new NoteStore(db,Clock.systemUTC());assertThat(link(source.noteId()).targetNoteId()).isEqualTo(target.noteId());long v=store.outgoing(space,source.noteId(),null,20).linkVersion();store=new NoteStore(db,Clock.systemUTC());assertThat(store.outgoing(space,source.noteId(),null,20).linkVersion()).isEqualTo(v);
        assertThatThrownBy(()->create("Too many","[[x]]".repeat(257))).isInstanceOf(ResponseStatusException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM note WHERE space_id=? AND title='Too many'",Long.class,space)).isZero();
    }
    @Test void legacyRenameReconcilesOldTitleAmbiguityBeforeReturningLinks() {
        var first=create("Same","");var second=create("Same","");var source=create("Source","[[Same]]");assertThat(link(source.noteId()).status()).isEqualTo("AMBIGUOUS");
        jdbc.update("UPDATE note SET title='Renamed' WHERE note_id=?",first.noteId());
        assertThat(link(source.noteId()).targetNoteId()).isEqualTo(second.noteId());assertThat(link(source.noteId()).status()).isEqualTo("RESOLVED");
    }
}
