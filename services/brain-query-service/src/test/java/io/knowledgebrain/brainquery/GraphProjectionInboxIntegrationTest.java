package io.knowledgebrain.brainquery;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.content.v1.*;
import io.knowledgebrain.contracts.content.links.v1.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_QUERY_DATABASE_URL",matches=".+")
class GraphProjectionInboxIntegrationTest {
 static UUID id(){var u=UUID.randomUUID();return new UUID((u.getMostSignificantBits()&~0xf000L)|0x7000L,u.getLeastSignificantBits());}
 static byte[] note(UUID space,UUID note,long version){var p=NoteChangedPayloadV1.newBuilder().setNoteId(note.toString()).setRevisionId(id().toString()).setVersion(version).setVisibility("PRIVATE").setState("ACTIVE").setTitle("Fixture "+version).setMarkdown("body");return EventEnvelopeV1.newBuilder().setEventId(id().toString()).setAggregateType("Note").setAggregateId(note.toString()).setSpaceId(space.toString()).setEventType("NoteUpdated").setEventVersion(1).setProducer("content-service").setOccurredAtEpochMillis(1).setTraceId("a".repeat(32)).setPayload(p.build().toByteString()).build().toByteArray();}
 static byte[] links(UUID space,UUID note,long version){var p=NoteLinksChangedPayloadV1.newBuilder().setSourceNoteId(note.toString()).setRevisionId(id().toString()).setNoteVersion(1).setLinkVersion(version).setSourceState("ACTIVE");return LinkEventEnvelopeV1.newBuilder().setEventId(id().toString()).setAggregateType("NoteLinks").setAggregateId(note.toString()).setSpaceId(space.toString()).setEventType("NoteLinksChanged").setEventVersion(1).setProducer("content-service").setOccurredAtEpochMillis(1).setTraceId("a".repeat(32)).setPayload(p.build().toByteString()).build().toByteArray();}
 PGSimpleDataSource source(){var d=new PGSimpleDataSource();d.setURL(System.getenv("BRAIN_TEST_QUERY_DATABASE_URL"));d.setUser("brain_brainquery");d.setPassword(System.getenv("BRAIN_TEST_QUERY_DATABASE_PASSWORD"));Flyway.configure().dataSource(d).locations("classpath:db/migration").load().migrate();return d;}
 @Test void independentStreamsSurviveRecreationAndRejectEventAndSpaceConflicts()throws Exception{
  var d=source();var inbox=new GraphProjectionInbox(d);var space=id();var n=id();var note=note(space,n,3);var links=links(space,n,8);
  inbox.stage("NOTE",note);inbox.stage("LINKS",links);inbox.stage("NOTE",note);inbox.stage("NOTE",note(space,n,2));
  var sql=new JdbcTemplate(d);assertEquals(2,sql.queryForObject("SELECT count(*) FROM note_graph_snapshot WHERE note_id=?",Integer.class,n));
  assertEquals(3,sql.queryForObject("SELECT version FROM note_graph_snapshot WHERE note_id=? AND kind='NOTE'",Long.class,n));assertEquals(8,sql.queryForObject("SELECT version FROM note_graph_snapshot WHERE note_id=? AND kind='LINKS'",Long.class,n));
  assertTrue(new GraphProjectionInbox(d).pending(10000).contains(new GraphProjectionInbox.Key(n,"LINKS")));
  assertThrows(GraphProjectionInbox.Conflict.class,()->inbox.stage("LINKS",links(id(),n,9)));
  var changed=EventEnvelopeV1.parseFrom(note);
  assertThrows(GraphProjectionInbox.Conflict.class,()->inbox.stage("NOTE",changed.toBuilder().setTraceId("b".repeat(32)).build().toByteArray()));
 }
 @Test @EnabledIfEnvironmentVariable(named="BRAIN_TEST_GRAPH_HOST",matches=".+")
 void realGraphCommitPrecedesAppliedMarkerAndPendingReplayIsIdempotent()throws Exception{
  var d=source();var space=id();var n=id();var inbox=new GraphProjectionInbox(d);var wire=note(space,n,1);var key=inbox.stage("NOTE",wire);
  var dir="/graph-projector";var trust="/graph-trust/truststore.p12";
  try(var graph=GraphConfiguration.open(System.getenv("BRAIN_TEST_GRAPH_HOST"),dir,trust)){
   inbox.project(key,graph);assertEquals(1,new JdbcTemplate(d).queryForObject("SELECT projected_version FROM note_graph_snapshot WHERE note_id=? AND kind='NOTE'",Long.class,n));
   inbox.project(key,graph);assertEquals(1,graph.read(space,10).nodes().size());
  }
 }
 @Test void linkTransportMustMatchKeySchemaAndTrace(){
  var n=id();var wire=links(id(),n,1);try{var e=LinkEventEnvelopeV1.parseFrom(wire);var h=Map.of("id",e.getEventId(),"schema_group","content","schema_artifact","note-links-events","schema_version","1","trace_id",e.getTraceId());GraphProjectionConsumer.validate("content.note-links.v1",wire,n.toString(),h);assertThrows(IllegalArgumentException.class,()->GraphProjectionConsumer.validate("content.note-links.v1",wire,id().toString(),h));assertThrows(IllegalArgumentException.class,()->GraphProjectionConsumer.validate("content.note-links.v1",wire,n.toString(),Map.of()));}catch(Exception e){throw new RuntimeException(e);}
 }
}
