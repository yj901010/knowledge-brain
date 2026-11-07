package io.knowledgebrain.content;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class MetadataNameStoreIntegrationTest {
 PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;final Clock clock=Clock.systemUTC();final UuidV7 ids=new UuidV7(clock);final String trace="f".repeat(32);
 @BeforeEach void prepare(){source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,clock);}
 @Test void substringPredicateRunsBeforeKeysetPagingForBothCatalogsAndRetainsUnusedDefinitions(){
  UUID space=ids.next(),actor=ids.next(),foreign=ids.next();var tags=new ArrayList<UUID>();var properties=new ArrayList<UUID>();
  for(int i=0;i<22;i++){String match="needle-"+i,miss="other-"+i;var note=store.create(space,actor,match,"",trace);var saved=store.metadata(space,note.noteId(),actor,0,new MetadataInput.Edit(List.of(match,miss),List.of(new MetadataInput.Field(match,"NUMBER","0"),new MetadataInput.Field(miss,"BOOLEAN","false"))),trace);tags.add(saved.tags().stream().filter(t->t.name().equals(match)).findFirst().orElseThrow().tagId());properties.add(saved.properties().stream().filter(p->p.name().equals(match)).findFirst().orElseThrow().propertyId());if(i==0)store.change(space,note.noteId(),actor,1,null,null,true,trace);if(i==1)store.metadata(space,note.noteId(),actor,1,new MetadataInput.Edit(List.of(),List.of()),trace);}
  var other=store.create(foreign,actor,"Foreign","",trace);store.metadata(foreign,other.noteId(),actor,0,new MetadataInput.Edit(List.of("needle-private"),List.of(new MetadataInput.Field("needle-private","STRING","\"secret\""))),trace);long before=events(space);
  for(boolean tag:new boolean[]{true,false}){var expected=tag?tags:properties;var first=store.definitions(space,tag,null,20,"needle");var tail=store.definitions(space,tag,first.nextAfter(),20,"needle");assertThat(first.items().stream().map(NoteMetadataStore.Definition::id)).containsExactlyElementsOf(expected.subList(0,20));assertThat(tail.items().stream().map(NoteMetadataStore.Definition::id)).containsExactlyElementsOf(expected.subList(20,22));assertThat(tail.nextAfter()).isNull();assertThat(first.items().subList(0,2)).allSatisfy(row->assertThat(row.activeNoteCount()).isZero());assertThat(first.items().subList(2,20)).allSatisfy(row->assertThat(row.activeNoteCount()).isEqualTo(1));assertThat(store.definitions(space,tag,null,100,"NEEDLE").items()).isEmpty();store=new NoteStore(source,clock);assertThat(store.definitions(space,tag,null,20,"needle")).isEqualTo(first);}
  assertThat(events(space)).isEqualTo(before);
 }
 @Test void unicodeCaseSpacesAndSqlWildcardCharactersStayLiteral(){
  UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"Literal","",trace);String literal="%_ '한글+&";var names=List.of(literal,"café","A B","🧠".repeat(64));var fields=names.stream().map(name->new MetadataInput.Field(name,"STRING","\"\"")).toList();store.metadata(space,note.noteId(),actor,0,new MetadataInput.Edit(names,fields),trace);long before=events(space);
  for(boolean tags:new boolean[]{true,false}){for(String q:List.of("%_",literal,"cafe\u0301"," ","🧠".repeat(64)))assertThat(store.definitions(space,tags,null,20,q).items()).isNotEmpty();assertThat(store.definitions(space,tags,null,20,"%_").items()).hasSize(1);assertThat(store.definitions(space,tags,null,20,"cafe\u0301").items().getFirst().name()).isEqualTo("café");assertThat(store.definitions(space,tags,null,20,"CAFÉ").items()).isEmpty();assertThat(store.definitions(space,tags,null,20,"missing").items()).isEmpty();}assertThat(events(space)).isEqualTo(before);
 }
 long events(UUID space){return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid IN (SELECT note_id FROM note WHERE space_id=?)",Long.class,space);}
}
