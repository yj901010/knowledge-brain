package io.knowledgebrain.brainquery;

import io.knowledgebrain.contracts.content.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.content.v1.NoteChangedPayloadV1;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure projection contract. Transport must authorize the Space before executing a query. */
public final class NoteSearchProjection {
    public static final String INDEX = "brain-notes-v1";
    private NoteSearchProjection() {}

    public record Revision(UUID eventId, UUID spaceId, UUID noteId, long version,
                           String state, String title, String markdown) {
        public Map<String, Object> source() {
            return Map.of("spaceId", spaceId.toString(), "noteId", noteId.toString(),
                    "visibility", "PRIVATE", "state", state, "title", title, "markdown", markdown);
        }
        // Retired notes remain versioned tombstones: deleting would allow late events to resurrect them.
        public Map<String, Object> indexOperation() {
            return Map.of("_index", INDEX, "_id", noteId.toString(),
                    "version", version, "version_type", "external");
        }
    }

    public static Revision decode(byte[] bytes) {
        if (bytes == null || bytes.length > 65536) throw new IllegalArgumentException("Invalid note event");
        try {
            var event = EventEnvelopeV1.parseFrom(bytes);
            if (!event.getProducer().equals("content-service") || !event.getAggregateType().equals("Note")
                    || event.getEventVersion() != 1
                    || !List.of("NoteCreated", "NoteUpdated", "NoteRetired").contains(event.getEventType()))
                throw new IllegalArgumentException("Unsupported note event");
            var note = NoteChangedPayloadV1.parseFrom(event.getPayload());
            var eventId = id(event.getEventId());
            var spaceId = id(event.getSpaceId());
            var noteId = id(note.getNoteId());
            id(note.getRevisionId());
            if (!noteId.equals(id(event.getAggregateId())) || note.getVersion() <= 0
                    || !note.getVisibility().equals("PRIVATE")
                    || !List.of("ACTIVE", "RETIRED").contains(note.getState())
                    || (event.getEventType().equals("NoteRetired") != note.getState().equals("RETIRED"))
                    || (event.getEventType().equals("NoteCreated") && note.getVersion() != 1)
                    || note.getTitle().isBlank() || note.getTitle().codePointCount(0, note.getTitle().length()) > 200
                    || note.getMarkdown().getBytes(StandardCharsets.UTF_8).length > 32768)
                throw new IllegalArgumentException("Invalid note revision");
            boolean retired = note.getState().equals("RETIRED");
            return new Revision(eventId, spaceId, noteId, note.getVersion(), note.getState(),
                    retired ? "" : note.getTitle(), retired ? "" : note.getMarkdown());
        } catch (InvalidProtocolBufferException e) {
            // Never include event contents in exceptions or logs: these are private notes.
            throw new IllegalArgumentException("Invalid note event");
        }
    }

    private static UUID id(String text) {
        UUID value;
        try { value = UUID.fromString(text); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid event identifier"); }
        if (!value.toString().equals(text) || value.version() != 7 || value.variant() != 2)
            throw new IllegalArgumentException("Invalid event identifier");
        return value;
    }

    /** Only accepts plain text, never caller-supplied query DSL or index names. */
    public static Map<String, Object> query(UUID authorizedSpace, String text, int offset, int size) {
        if (authorizedSpace == null || text == null || text.isBlank()
                || text.codePointCount(0, text.length()) > 200 || text.codePoints().anyMatch(Character::isISOControl)
                || size < 1 || size > 50 || offset < 0 || offset > 1000 - size)
            throw new IllegalArgumentException("Invalid search request");
        var filters = List.of(Map.of("term", Map.of("spaceId", authorizedSpace.toString())),
                Map.of("term", Map.of("visibility", "PRIVATE")), Map.of("term", Map.of("state", "ACTIVE")));
        var match = Map.of("multi_match", Map.of("query", text.strip(), "fields", List.of("title^3", "markdown"),
                "type", "best_fields", "operator", "and"));
        return Map.of("query", Map.of("bool", Map.of("filter", filters, "must", List.of(match))),
                "from", offset, "size", size, "track_total_hits", true,
                "_source", List.of("noteId", "title"),
                "sort", List.of(Map.of("_score", "desc"), Map.of("noteId", "asc")));
    }
}
