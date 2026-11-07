package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_DATABASE_URL",matches=".+")
class MetadataStoreIntegrationTest {
 PGSimpleDataSource source;JdbcTemplate jdbc;NoteStore store;UuidV7 ids=new UuidV7(Clock.systemUTC());String trace="c".repeat(32);
 @BeforeEach void setup(){source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);store=new NoteStore(source,Clock.systemUTC());}
 MetadataInput.Edit edit(String name){return new MetadataInput.Edit(List.of(name),List.of(new MetadataInput.Field("score","NUMBER","3.125")));}
 @Test void stableSpaceDefinitionsIndependentVersionsAndRestartHistory(){
 UUID space=ids.next(),actor=ids.next();var a=store.create(space,actor,"a","",trace);var b=store.create(space,actor,"b","",trace);assertThat(store.metadata(space,a.noteId()).metadataVersion()).isZero();
 var first=store.metadata(space,a.noteId(),actor,0,edit("study"),trace);var second=store.metadata(space,b.noteId(),actor,0,edit("study"),trace);assertThat(first.tags()).isEqualTo(second.tags());assertThat(first.properties()).isEqualTo(second.properties());assertThat(store.definitions(space,true,null,20).items().getFirst().activeNoteCount()).isEqualTo(2);
 store.change(space,a.noteId(),actor,1,"new","body",false,trace);assertThat(store.metadata(space,a.noteId())).isEqualTo(first);store=new NoteStore(source,Clock.systemUTC());assertThat(store.metadataHistory(space,a.noteId(),100,10).getFirst().snapshot()).isEqualTo(first);
 store.metadata(space,a.noteId(),actor,1,new MetadataInput.Edit(List.of(),List.of()),trace);assertThat(store.definitions(space,true,null,20).items().getFirst().activeNoteCount()).isEqualTo(1);
 store.change(space,b.noteId(),actor,1,null,null,true,trace);assertThat(store.definitions(space,true,null,20).items().getFirst().activeNoteCount()).isZero();assertThat(store.metadataHistory(space,b.noteId(),100,10)).hasSize(2);assertThat(store.metadataHistory(space,a.noteId(),2,1).getFirst().metadataVersion()).isEqualTo(1);
 UUID other=ids.next();var c=store.create(other,actor,"c","",trace);assertThat(store.metadata(other,c.noteId(),actor,0,edit("study"),trace).tags()).isNotEqualTo(first.tags());assertThatThrownBy(()->store.metadata(other,a.noteId())).isInstanceOf(ResponseStatusException.class);
 }
 @Test void conflictingTypeAndOutboxFailureRollbackEverything(){
 UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"a","",trace);var first=store.metadata(space,note.noteId(),actor,0,edit("study"),trace);
 var conflict=new MetadataInput.Edit(List.of("lost"),List.of(new MetadataInput.Field("score","STRING","\"bad\"")));assertThatThrownBy(()->store.metadata(space,note.noteId(),actor,1,conflict,trace)).isInstanceOf(ResponseStatusException.class);assertThat(store.metadata(space,note.noteId())).isEqualTo(first);assertThat(store.definitions(space,true,null,20).items()).hasSize(1);
 jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT metadata_test_rollback CHECK(trace_id <> 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb') NOT VALID");try{assertThatThrownBy(()->store.metadata(space,note.noteId(),actor,1,edit("lost"),"b".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(store.metadata(space,note.noteId())).isEqualTo(first);assertThat(store.metadataHistory(space,note.noteId(),100,10)).hasSize(1);assertThat(store.definitions(space,true,null,20).items()).hasSize(1);}finally{jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT metadata_test_rollback");}
 }
 @Test void exactDecimalSurvivesCanonicalReadHistoryAndOutbox() throws Exception {
 UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"decimal","",trace);
 String number="12345678901234567890.123456789012345678";
 var input=MetadataInput.parse(("{\"tags\":[],\"properties\":{\"precise\":{\"type\":\"NUMBER\",\"value\":"+number+"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
 var written=store.metadata(space,note.noteId(),actor,0,input,trace);
 assertThat(new java.math.BigDecimal(written.properties().getFirst().value().toString())).isEqualByComparingTo(number);
 store=new NoteStore(source,Clock.systemUTC());
 assertThat(new java.math.BigDecimal(store.metadata(space,note.noteId()).properties().getFirst().value().toString())).isEqualByComparingTo(number);
 assertThat(new java.math.BigDecimal(store.metadataHistory(space,note.noteId(),100,10).getFirst().snapshot().properties().getFirst().value().toString())).isEqualByComparingTo(number);
 byte[] bytes=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregatetype='note-metadata' AND aggregateid=?",byte[].class,note.noteId());
 var event=io.knowledgebrain.contracts.content.metadata.v1.MetadataEventEnvelopeV1.parseFrom(bytes);
 var payload=io.knowledgebrain.contracts.content.metadata.v1.NoteMetadataChangedPayloadV1.parseFrom(event.getPayload());
 assertThat(new java.math.BigDecimal(payload.getProperties(0).getJsonValue())).isEqualByComparingTo(number);
 }
 @Test void concurrentMetadataWritersHaveOneWinner() throws Exception {
 UUID space=ids.next(),actor=ids.next();var note=store.create(space,actor,"a","",trace);try(var workers=Executors.newVirtualThreadPerTaskExecutor()){var start=new CountDownLatch(1);var futures=new ArrayList<Future<Integer>>();for(int i=0;i<4;i++)futures.add(workers.submit(()->{start.await();try{store.metadata(space,note.noteId(),actor,0,edit("study"),trace);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}}));start.countDown();var codes=new ArrayList<Integer>();for(var f:futures)codes.add(f.get(20,TimeUnit.SECONDS));assertThat(Collections.frequency(codes,200)).isEqualTo(1);assertThat(codes).containsOnly(200,412);}assertThat(store.metadataHistory(space,note.noteId(),100,10)).hasSize(1);
 }
}
