package io.knowledgebrain.brainquery;

import io.knowledgebrain.contracts.content.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.content.v1.NoteChangedPayloadV1;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_QUERY_DATABASE_URL",matches=".+")
class NoteProjectionStoreIntegrationTest {
    private String schema;
    private JdbcTemplate fixture;
    private UUID id() { var id=UUID.randomUUID();return new UUID((id.getMostSignificantBits() & ~0xf000L)|0x7000L,id.getLeastSignificantBits()); }
    private PGSimpleDataSource source() {
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_QUERY_DATABASE_URL"));source.setUser("brain_brainquery");source.setPassword(System.getenv("BRAIN_TEST_QUERY_DATABASE_PASSWORD"));
        // Live projector workers must not consume this test's pending fixture.
        schema="note_search_test_"+UUID.randomUUID().toString().replace("-","");
        source.setCurrentSchema(schema);
        Flyway.configure().dataSource(source).schemas(schema).locations("classpath:db/migration").load().migrate();fixture=new JdbcTemplate(source);return source;
    }
    @org.junit.jupiter.api.AfterEach void cleanup(){if(schema!=null){if(!schema.matches("note_search_test_[0-9a-f]{32}"))throw new IllegalStateException("Invalid test schema");fixture.execute("DROP SCHEMA "+schema+" CASCADE");}}
    private EventEnvelopeV1 event(UUID note,UUID space,long version,String title) {
        var payload=NoteChangedPayloadV1.newBuilder().setNoteId(note.toString()).setRevisionId(id().toString()).setVersion(version).setState("ACTIVE").setVisibility("PRIVATE").setTitle(title).setMarkdown("private fixture").build();
        return EventEnvelopeV1.newBuilder().setEventId(id().toString()).setProducer("content-service").setAggregateType("Note").setAggregateId(note.toString()).setSpaceId(space.toString()).setEventVersion(1).setEventType("NoteUpdated").setOccurredAtEpochMillis(1).setTraceId("b".repeat(32)).setPayload(payload.toByteString()).build();
    }
    @Test void inboxAndPendingProjectionSurviveProcessRecreationAndDuplicateDelivery() {
        var source=source();var store=new NoteProjectionStore(source);var note=id();var event=event(note,id(),2,"Durable");
        store.stage(event.toByteArray());store.stage(event.toByteArray());
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_search_inbox WHERE event_id=?",Long.class,UUID.fromString(event.getEventId()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT projected_version FROM note_search_snapshot WHERE note_id=?",Long.class,note)).isZero();
        var recreated=new NoteProjectionStore(source);
        assertThat(recreated.pending(10000)).anyMatch(wire->NoteSearchProjection.decode(wire).noteId().equals(note));
        recreated.applied(NoteSearchProjection.decode(event.toByteArray()));
        assertThat(jdbc.queryForObject("SELECT projected_version FROM note_search_snapshot WHERE note_id=?",Long.class,note)).isEqualTo(2);
    }
    @Test void higherVersionSurvivesOldEventsAndConflictingEventIdRollsBack() throws Exception {
        var source=source();var store=new NoteProjectionStore(source);var note=id();var space=id();var newer=event(note,space,3,"Newer");
        store.stage(newer.toByteArray());
        assertThat(NoteSearchProjection.decode(store.stage(event(note,space,1,"Older").toByteArray())).version()).isEqualTo(3);
        var altered=newer.toBuilder().setTraceId("c".repeat(32)).build();
        assertThatThrownBy(()->store.stage(altered.toByteArray())).isInstanceOf(NoteSearchClient.Conflict.class);
        var conflicting=event(note,space,3,"Conflict");
        assertThatThrownBy(()->store.stage(conflicting.toByteArray())).isInstanceOf(NoteSearchClient.Conflict.class);
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_search_inbox WHERE event_id=?",Long.class,UUID.fromString(conflicting.getEventId()))).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM note_search_snapshot WHERE note_id=?",Long.class,note)).isEqualTo(3);
    }
    @Test void concurrentConsumersPreserveHighestVersionAndImmutableSpace() throws Exception {
        var source=source();var store=new NoteProjectionStore(source);var note=id();var space=id();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=workers.invokeAll(List.of(()->store.stage(event(note,space,2,"Two").toByteArray()),()->store.stage(event(note,space,3,"Three").toByteArray())));
            for(var result:results)result.get();
        }
        assertThat(new JdbcTemplate(source).queryForObject("SELECT version FROM note_search_snapshot WHERE note_id=?",Long.class,note)).isEqualTo(3);
        assertThatThrownBy(()->store.stage(event(note,id(),4,"Other Space").toByteArray())).isInstanceOf(NoteSearchClient.Conflict.class);
    }
    @Test void mismatchedKeyOrTraceCannotReachTheInbox() {
        var event=event(id(),id(),1,"Transport");
        var headers=Map.of("id",event.getEventId(),"schema_group","content","schema_artifact","note-events","schema_version","1","trace_id",event.getTraceId());
        NoteProjectionConsumer.validate(event.toByteArray(),event.getAggregateId(),headers);
        assertThatThrownBy(()->NoteProjectionConsumer.validate(event.toByteArray(),id().toString(),headers)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NoteProjectionConsumer.validate(event.toByteArray(),event.getAggregateId(),Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
