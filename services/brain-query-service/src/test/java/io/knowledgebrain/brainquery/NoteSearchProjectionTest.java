package io.knowledgebrain.brainquery;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.content.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.content.v1.NoteChangedPayloadV1;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class NoteSearchProjectionTest {
    private static final UUID NOTE = UUID.fromString("019a1234-5678-7000-8000-000000000001");
    private static final UUID SPACE = UUID.fromString("019a1234-5678-7000-8000-000000000002");
    private EventEnvelopeV1 event(long version, boolean retired) {
        var note = NoteChangedPayloadV1.newBuilder().setNoteId(NOTE.toString())
                .setRevisionId("019a1234-5678-7000-8000-000000000003").setVersion(version)
                .setState(retired ? "RETIRED" : "ACTIVE").setVisibility("PRIVATE")
                .setTitle("Private title").setMarkdown("Private body").build();
        return EventEnvelopeV1.newBuilder().setEventId("019a1234-5678-7000-8000-000000000004")
                .setProducer("content-service").setAggregateType("Note").setAggregateId(NOTE.toString())
                .setSpaceId(SPACE.toString()).setEventVersion(1).setEventType(retired ? "NoteRetired" : "NoteUpdated")
                .setPayload(note.toByteString()).build();
    }
    @Test void retiredRevisionKeepsVersionButErasesSearchablePrivateText() {
        var revision = NoteSearchProjection.decode(event(9, true).toByteArray());
        assertThat(revision.version()).isEqualTo(9);
        assertThat(revision.source()).containsEntry("state", "RETIRED").containsEntry("title", "").containsEntry("markdown", "");
        assertThat(revision.indexOperation()).containsEntry("_id", NOTE.toString()).containsEntry("version_type", "external");
        assertThat(NoteSearchProjection.decode(event(9, true).toByteArray())).isEqualTo(revision);
    }
    @Test void wrongDomainAndCrossAggregatePayloadAreRejected() {
        assertThatThrownBy(() -> NoteSearchProjection.decode(event(1, false).toBuilder().setAggregateType("NoteMetadata").build().toByteArray()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NoteSearchProjection.decode(event(1, false).toBuilder().setAggregateId(SPACE.toString()).build().toByteArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unknownVisibilityAndUnsignedVersionOverflowAreRejected() {
        var original = event(1, false);
        for (var note : new NoteChangedPayloadV1[] {
                payload(original).toBuilder().setVisibility("PUBLIC").build(),
                payload(original).toBuilder().setVersion(-1).build(),
                payload(original).toBuilder().setState("RETIRED").build()}) {
            assertThatThrownBy(() -> NoteSearchProjection.decode(original.toBuilder().setPayload(note.toByteString()).build().toByteArray()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    private NoteChangedPayloadV1 payload(EventEnvelopeV1 event) {
        try { return NoteChangedPayloadV1.parseFrom(event.getPayload()); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test void malformedBytesDoNotLeakPrivatePayload() {
        assertThatThrownBy(() -> NoteSearchProjection.decode(new byte[] {(byte)255}))
                .hasMessage("Invalid note event");
        assertThatThrownBy(() -> NoteSearchProjection.decode(new byte[65537]))
                .hasMessage("Invalid note event");
    }
    @SuppressWarnings("unchecked")
    @Test void queryScopesHitsAndTotalsAndTreatsDslCharactersAsText() {
        String text = "* OR spaceId:other";
        var query = NoteSearchProjection.query(SPACE, text, 0, 20);
        var bool = (Map<String,Object>) ((Map<String,Object>)query.get("query")).get("bool");
        assertThat(bool.get("filter").toString()).contains(SPACE.toString(), "visibility=PRIVATE", "state=ACTIVE");
        assertThat(bool.get("must").toString()).contains("multi_match", "query=" + text).doesNotContain("query_string");
        assertThat(query).containsEntry("track_total_hits", true);
        assertThat(query.get("_source").toString()).doesNotContain("markdown", "actorId", "eventId");
    }
    @Test void invalidQueryAndDeepPaginationAreRejected() {
        for (String text : new String[] {"", "  ", "x\nsecret", "x".repeat(201)})
            assertThatThrownBy(() -> NoteSearchProjection.query(SPACE, text, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NoteSearchProjection.query(null, "text", 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NoteSearchProjection.query(SPACE, "text", 999, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NoteSearchProjection.query(SPACE, "text", 0, 51)).isInstanceOf(IllegalArgumentException.class);
    }
}
