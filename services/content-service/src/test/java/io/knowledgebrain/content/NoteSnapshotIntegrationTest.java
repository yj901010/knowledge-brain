package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class NoteSnapshotIntegrationTest {
 String schema;org.springframework.jdbc.core.JdbcTemplate fixture;
 PGSimpleDataSource source(){var db=new PGSimpleDataSource();db.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));db.setUser("brain_content");db.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));schema="note_snapshot_test_"+UUID.randomUUID().toString().replace("-","");db.setCurrentSchema(schema);Flyway.configure().dataSource(db).schemas(schema).locations("classpath:db/migration").load().migrate();fixture=new org.springframework.jdbc.core.JdbcTemplate(db);return db;}
 @org.junit.jupiter.api.AfterEach void cleanup(){if(schema!=null){if(!schema.matches("note_snapshot_test_[0-9a-f]{32}"))throw new IllegalStateException("Invalid test schema");fixture.execute("DROP SCHEMA "+schema+" CASCADE");}}

 @Test void canonicalLinksUseCurrentIndependentLinkVersionAndRetiredEmptySnapshot()throws Exception {
  var db=source();var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();var notes=new NoteStore(db,Clock.systemUTC());
  String title="SnapshotTarget "+ids.next();var source=notes.create(space,actor,"SnapshotSource","[["+title+"]]","d".repeat(32));var target=notes.create(space,actor,title,"","d".repeat(32));
  var store=new NoteSnapshotStore(db);var page=store.links(null,target.noteId(),5);UUID after=null;var found=new ArrayList<NoteSnapshotStore.LinkItem>();
  do{page=store.links(after,target.noteId(),5);found.addAll(page.items());after=page.nextAfter();}while(after!=null);
  var item=found.stream().filter(n->n.noteId().equals(source.noteId())).findFirst().orElseThrow();
  var data=io.knowledgebrain.contracts.content.links.v1.NoteLinksChangedPayloadV1.parseFrom(Base64.getDecoder().decode(item.payload()));
  assertThat(data.getNoteVersion()).isEqualTo(1);assertThat(data.getLinkVersion()).isGreaterThan(1);assertThat(data.getLinks(0).getTargetNoteId()).isEqualTo(target.noteId().toString());
  notes.change(space,source.noteId(),actor,1,null,null,true,"d".repeat(32));
  var last=store.links(source.noteId(),target.noteId(),5);assertThat(last.items()).noneMatch(n->n.noteId().equals(source.noteId()));
  var sql=new org.springframework.jdbc.core.JdbcTemplate(db);byte[] payload=sql.queryForObject("SELECT r.payload FROM note_link_revision r JOIN note n ON n.note_id=r.source_note_id AND n.link_version=r.link_version WHERE n.note_id=?",byte[].class,source.noteId());
  var retired=io.knowledgebrain.contracts.content.links.v1.NoteLinksChangedPayloadV1.parseFrom(payload);assertThat(retired.getSourceState()).isEqualTo("RETIRED");assertThat(retired.getLinksCount()).isZero();
 }
 @Test void canonicalPaginationIncludesLatestRevisionAndPrivateRetiredTombstone() {
  var db=source();var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();var notes=new NoteStore(db,Clock.systemUTC());
  var active=notes.create(space,actor,"snapshot","original","c".repeat(32));notes.change(space,active.noteId(),actor,1,"latest","latest body",false,"c".repeat(32));
  var retired=notes.create(space,actor,"private secret","secret body","c".repeat(32));notes.change(space,retired.noteId(),actor,1,null,null,true,"c".repeat(32));
  var snapshot=new NoteSnapshotStore(db);UUID cursor=null,through=null;var found=new ArrayList<NoteSnapshotStore.Item>();
  do {var page=snapshot.page(cursor,through,1);if(through==null)through=page.through();else assertThat(page.through()).isEqualTo(through);found.addAll(page.items());cursor=page.nextAfter();}while(cursor!=null);
  var live=found.stream().filter(n->n.noteId().equals(active.noteId())).findFirst().orElseThrow();assertThat(live.version()).isEqualTo(2);assertThat(live.markdown()).isEqualTo("latest body");assertThat(live.revisionId()).isNotNull();
  var tombstone=found.stream().filter(n->n.noteId().equals(retired.noteId())).findFirst().orElseThrow();assertThat(tombstone.state()).isEqualTo("RETIRED");assertThat(tombstone.title()).isEmpty();assertThat(tombstone.markdown()).isEmpty();assertThat(tombstone.visibility()).isEqualTo("PRIVATE");
  var later=notes.create(space,actor,"later","", "c".repeat(32));assertThat(snapshot.page(active.noteId(),retired.noteId(),5).items()).noneMatch(n->n.noteId().equals(later.noteId()));
 }
}
