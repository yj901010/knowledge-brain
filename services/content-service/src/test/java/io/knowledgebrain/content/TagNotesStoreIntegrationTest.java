package io.knowledgebrain.content;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class TagNotesStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;final Clock clock=Clock.systemUTC();final UuidV7 ids=new UuidV7(clock);final String trace="d".repeat(32);
    @BeforeEach void prepare(){source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,clock);}
    UUID assign(UUID space,UUID note,UUID actor,long version,String name){return store.metadata(space,note,actor,version,new MetadataInput.Edit(List.of(name),List.of()),trace).tags().getFirst().tagId();}
    @Test void canonicalTagKeysetsExcludeUnassignedRetiredAndForeignNotesWithoutWrites(){
        UUID space=ids.next(),actor=ids.next(),foreign=ids.next(),tag=null;var expected=new ArrayList<UUID>();
        for(int i=0;i<22;i++){var n=store.create(space,actor,"Repeated title","body",trace);tag=assign(space,n.noteId(),actor,0,"study");expected.add(n.noteId());}
        var daily=store.openDaily(space,"2026-10-22","Asia/Seoul",actor,trace).entry().note();assign(space,daily.noteId(),actor,0,"study");expected.add(daily.noteId());
        var retired=store.create(space,actor,"Retired","",trace);assign(space,retired.noteId(),actor,0,"study");store.change(space,retired.noteId(),actor,1,null,null,true,trace);
        store.create(space,actor,"Untagged","",trace);var other=store.create(foreign,actor,"Foreign secret","",trace);UUID foreignTag=assign(foreign,other.noteId(),actor,0,"study");assertThat(foreignTag).isNotEqualTo(tag);
        long events=events(space);var first=store.tagNotes(space,tag,null,20);var tail=store.tagNotes(space,tag,first.nextAfter(),20);var actual=new ArrayList<UUID>();first.items().forEach(n->actual.add(n.noteId()));tail.items().forEach(n->actual.add(n.noteId()));assertThat(actual).containsExactlyElementsOf(expected);assertThat(first.items()).hasSize(20);assertThat(tail.items()).hasSize(3);assertThat(tail.nextAfter()).isNull();
        assertThat(store.definitions(space,true,null,20).items().getFirst().activeNoteCount()).isEqualTo(23);store=new NoteStore(source,clock);assertThat(store.tagNotes(space,tag,null,20)).isEqualTo(first);assertThat(store.tagNotes(space,tag,expected.getLast(),20).items()).isEmpty();
        assertThatThrownBy(()->store.tagNotes(space,foreignTag,null,20)).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.tagNotes(space,ids.next(),null,20)).isInstanceOf(ResponseStatusException.class);assertThat(events(space)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND indexname='note_tag_lookup'",String.class)).contains("space_id, tag_id, note_id");
    }
    @Test void liveMembershipTracksRemovalReassignmentRetirementAndLatestTitle(){
        UUID space=ids.next(),actor=ids.next();var n=store.create(space,actor,"Original","body",trace);UUID tag=assign(space,n.noteId(),actor,0,"study");store.change(space,n.noteId(),actor,1,"Latest","updated",false,trace);
        assertThat(store.tagNotes(space,tag,null,20).items().getFirst()).satisfies(row->{assertThat(row.title()).isEqualTo("Latest");assertThat(row.version()).isEqualTo(2);});
        UUID different=assign(space,n.noteId(),actor,1,"Study");assertThat(different).isNotEqualTo(tag);assertThat(store.tagNotes(space,tag,null,20).items()).isEmpty();assertThat(assign(space,n.noteId(),actor,2,"study")).isEqualTo(tag);assertThat(store.tagNotes(space,different,null,20).items()).isEmpty();
        store.change(space,n.noteId(),actor,2,null,null,true,trace);assertThat(store.tagNotes(space,tag,null,20).items()).isEmpty();assertThat(store.definitions(space,true,null,20).items()).hasSize(2).allSatisfy(row->assertThat(row.activeNoteCount()).isZero());assertThat(store.metadataHistory(space,n.noteId(),100,10)).hasSize(4);assertThat(store.history(space,n.noteId(),100,10)).hasSize(3);
    }
    long events(UUID space){return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN (SELECT note_id FROM note WHERE space_id=?)",Long.class,space);}
}
