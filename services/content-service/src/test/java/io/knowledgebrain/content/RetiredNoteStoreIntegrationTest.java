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
class RetiredNoteStoreIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;
    final Clock clock=Clock.systemUTC();final UuidV7 ids=new UuidV7(clock);final String trace="b".repeat(32);
    @BeforeEach void prepare(){
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,clock);
    }
    @Test void retiredKeysetsRemainPrivateOrderedAndReadOnlyAcrossRestart(){
        UUID space=ids.next(),foreign=ids.next(),actor=ids.next();var retiredIds=new ArrayList<UUID>();
        for(int i=0;i<23;i++){
            var note=store.create(space,actor,"Retired duplicate title","original "+i,trace);store.change(space,note.noteId(),actor,1,null,null,true,trace);retiredIds.add(note.noteId());
            store.create(space,actor,"Active "+i,"active",trace);
        }
        var other=store.create(foreign,actor,"Private foreign","secret",trace);store.change(foreign,other.noteId(),actor,1,null,null,true,trace);
        long count=events(space),revisions=revisions(space);var first=store.retired(space,null,20);assertThat(first.items()).hasSize(20);assertThat(first.nextAfter()).isEqualTo(first.items().getLast().noteId());
        var second=store.retired(space,first.nextAfter(),20);assertThat(second.items()).hasSize(3);assertThat(second.nextAfter()).isNull();
        var actual=new ArrayList<UUID>();first.items().forEach(n->actual.add(n.noteId()));second.items().forEach(n->actual.add(n.noteId()));assertThat(actual).containsExactlyElementsOf(retiredIds);assertThat(actual).doesNotContain(other.noteId());
        assertThat(first.items()).allSatisfy(n->{assertThat(n.version()).isEqualTo(2);assertThat(n.title()).isEqualTo("Retired duplicate title");});
        assertThat(store.list(space,null,100).items()).hasSize(23).noneSatisfy(n->assertThat(n.title()).isEqualTo("Retired duplicate title"));
        store=new NoteStore(source,clock);assertThat(store.retired(space,null,20)).isEqualTo(first);assertThat(store.retired(space,retiredIds.getLast(),20).items()).isEmpty();assertThat(store.retired(ids.next(),null,20).items()).isEmpty();
        assertThatThrownBy(()->store.history(foreign,retiredIds.getFirst(),Long.MAX_VALUE,5)).isInstanceOf(ResponseStatusException.class);
        assertThat(events(space)).isEqualTo(count);assertThat(revisions(space)).isEqualTo(revisions);
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND indexname='note_space_retired_idx'",String.class)).contains("space_id, note_id","PRIVATE","RETIRED");
    }
    @Test void retiredDailyNoteHistoryAndDateBindingSurviveWithoutReactivation(){
        UUID space=ids.next(),actor=ids.next();String original="<script>not executable</script>\n# 기록 🧠";var note=store.create(space,actor,"Daily original",original,trace);
        var daily=new DailyNoteStore(jdbc,clock);daily.bind(space,LocalDate.of(2026,10,22),"Asia/Seoul",note.noteId());
        for(int v=1;v<7;v++)store.change(space,note.noteId(),actor,v,"Daily latest","version "+(v+1),false,trace);
        store.change(space,note.noteId(),actor,7,null,null,true,trace);long events=events(space);
        var summary=store.retired(space,null,20).items().getFirst();assertThat(summary.noteId()).isEqualTo(note.noteId());assertThat(summary.version()).isEqualTo(8);assertThat(summary.title()).isEqualTo("Daily latest");
        var head=store.history(space,note.noteId(),Long.MAX_VALUE,5);var tail=store.history(space,note.noteId(),head.getLast().version(),5);assertThat(head).hasSize(5);assertThat(tail).hasSize(3);assertThat(head.getFirst().state()).isEqualTo("RETIRED");assertThat(tail.getLast().markdown()).isEqualTo(original);
        assertThatThrownBy(()->store.get(space,note.noteId())).isInstanceOf(ResponseStatusException.class);assertThatThrownBy(()->store.change(space,note.noteId(),actor,8,"Resurrect","body",false,trace)).isInstanceOf(ResponseStatusException.class);
        assertThat(daily.binding(space,LocalDate.of(2026,10,22))).isEqualTo(new DailyNoteStore.Binding(note.noteId(),"Asia/Seoul"));assertThat(daily.list(space,null,null,null,20).items()).isEmpty();assertThat(events(space)).isEqualTo(events);assertThat(revisions(space)).isEqualTo(8);
    }
    long events(UUID space){return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN (SELECT note_id FROM note WHERE space_id=?)",Long.class,space);}
    long revisions(UUID space){return jdbc.queryForObject("SELECT count(*) FROM note_revision r JOIN note n USING(note_id) WHERE n.space_id=?",Long.class,space);}
}
