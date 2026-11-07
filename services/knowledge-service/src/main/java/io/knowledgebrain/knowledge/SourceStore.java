package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.source.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.knowledge.source.v1.SourceChangedPayloadV1;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

final class SourceStore {
    record Fields(String title, String url, String citation, String licenseNote) {}
    record Source(UUID sourceId, UUID spaceId, String title, String url, String citation, String licenseNote,
                  String visibility, String state, long version, Instant createdAt, Instant updatedAt) {}
    record Revision(UUID revisionId, UUID sourceId, String title, String url, String citation, String licenseNote,
                    long version, String state, UUID actorId, Instant occurredAt) {}
    record Page(List<Source> items, UUID nextAfter) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;

    SourceStore(DataSource source, Clock clock) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15); this.clock = clock; ids = new UuidV7(clock);
    }

    static Fields fields(String title, String url, String citation, String licenseNote) {
        if (title == null) throw bad("Source title required");
        title = title.strip(); text(title, 200); if (title.isEmpty()) throw bad("Source title required");
        text(citation, 4000); text(licenseNote, 1000);
        if (url != null) {
            url = url.strip();
            if (url.isEmpty()) url = null;
            else {
                if (url.length() > 2048 || url.chars().anyMatch(c -> c <= 32 || c >= 127 || c == '\\')) throw bad("Invalid Source URL");
                try {
                    URI uri = new URI(url);
                    if (!uri.isAbsolute() || uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                        || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535
                        || uri.getRawAuthority().endsWith(":") || uri.getRawAuthority().contains("%")) throw new IllegalArgumentException();
                } catch (Exception error) { throw bad("Absolute HTTP or HTTPS Source URL required"); }
            }
        }
        if (url == null && citation.isBlank()) throw bad("Source URL or citation required");
        return new Fields(title, url, citation, licenseNote);
    }
    private static void text(String value, int maximum) {
        if (value == null || value.codePointCount(0, value.length()) > maximum || value.indexOf('\0') >= 0) throw bad("Invalid Source text");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw bad("Invalid Source text");
            } else if (Character.isLowSurrogate(c)) throw bad("Invalid Source text");
        }
    }

    Source create(UUID space, UUID actor, String title, String url, String citation, String licenseNote, String trace) {
        ConceptStore.id(space); ConceptStore.id(actor); trace(trace); var fields = fields(title, url, citation, licenseNote);
        return transaction.execute(status -> {
            UUID source = ids.next(); Instant now = clock.instant();
            jdbc.update("INSERT INTO knowledge_source(source_id,space_id,title,url,citation,license_note,visibility,state,version,created_at,updated_at) VALUES (?,?,?,?,?,?,'PRIVATE','ACTIVE',1,?,?)",
                source, space, fields.title(), fields.url(), fields.citation(), fields.licenseNote(), Timestamp.from(now), Timestamp.from(now));
            Source result = load(space, source, false); revisionAndEvent(result, actor, trace, "SourceCreated"); return result;
        });
    }
    Source get(UUID space, UUID source) {
        identify(space, source); Source result = load(space, source, false);
        if (result == null || !"ACTIVE".equals(result.state())) throw missing(); return result;
    }
    Page list(UUID space, UUID after, int limit) {
        return listState(space,after,limit,"ACTIVE");
    }
    Page retired(UUID space, UUID after, int limit) {
        return listState(space,after,limit,"RETIRED");
    }
    private Page listState(UUID space, UUID after, int limit, String state) {
        ConceptStore.id(space); if (after != null) ConceptStore.id(after); limit(limit, 100);
        String lifecycle="RETIRED".equals(state)?"state='RETIRED'":"state='ACTIVE'";
        String sql = "SELECT * FROM knowledge_source WHERE space_id=? AND visibility='PRIVATE' AND " + lifecycle + (after == null ? "" : " AND source_id>?") + " ORDER BY source_id LIMIT ?";
        Object[] args = after == null ? new Object[]{space, limit + 1} : new Object[]{space, after, limit + 1};
        var rows = jdbc.query(sql, (rs, n) -> read(rs), args); boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, more ? items.getLast().sourceId() : null);
    }
    Source change(UUID space, UUID source, UUID actor, long expected, String title, String url, String citation, String licenseNote, boolean retire, String trace) {
        identify(space, source); ConceptStore.id(actor); trace(trace); if (expected < 1) throw bad("Positive Source version required");
        var fields = retire ? null : fields(title, url, citation, licenseNote);
        return transaction.execute(status -> {
            Source prior = load(space, source, true);
            if (prior == null || !"ACTIVE".equals(prior.state())) throw missing();
            if (prior.version() != expected) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Source changed; reload before saving");
            if (prior.version() == Long.MAX_VALUE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Source version limit reached");
            jdbc.update("UPDATE knowledge_source SET title=?,url=?,citation=?,license_note=?,state=?,version=version+1,updated_at=? WHERE space_id=? AND source_id=?",
                retire ? prior.title() : fields.title(), retire ? prior.url() : fields.url(), retire ? prior.citation() : fields.citation(), retire ? prior.licenseNote() : fields.licenseNote(),
                retire ? "RETIRED" : "ACTIVE", Timestamp.from(clock.instant()), space, source);
            Source result = load(space, source, false); revisionAndEvent(result, actor, trace, retire ? "SourceRetired" : "SourceUpdated"); return result;
        });
    }
    List<Revision> history(UUID space, UUID source, long before, int limit) {
        identify(space, source); limit(limit, 10); if (before < 1) throw bad("Invalid Source revision cursor");
        if (load(space, source, false) == null) throw missing();
        return jdbc.query("SELECT revision_id,source_id,title,url,citation,license_note,version,state,actor_id,occurred_at FROM source_revision WHERE source_id=? AND version<? ORDER BY version DESC LIMIT ?",
            (rs, n) -> new Revision(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getObject(9, UUID.class), rs.getTimestamp(10).toInstant()), source, before, limit);
    }
    private Source load(UUID space, UUID source, boolean lock) {
        var rows = jdbc.query("SELECT * FROM knowledge_source WHERE space_id=? AND source_id=? AND visibility='PRIVATE'" + (lock ? " FOR UPDATE" : ""), (rs, n) -> read(rs), space, source);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static Source read(ResultSet rs) throws SQLException {
        return new Source(rs.getObject("source_id", UUID.class), rs.getObject("space_id", UUID.class), rs.getString("title"), rs.getString("url"), rs.getString("citation"), rs.getString("license_note"),
            rs.getString("visibility"), rs.getString("state"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
    private void revisionAndEvent(Source source, UUID actor, String trace, String type) {
        UUID revision = ids.next(), event = ids.next(); Instant now = clock.instant();
        jdbc.update("INSERT INTO source_revision(revision_id,source_id,title,url,citation,license_note,version,state,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            revision, source.sourceId(), source.title(), source.url(), source.citation(), source.licenseNote(), source.version(), source.state(), actor, Timestamp.from(now));
        var payload = SourceChangedPayloadV1.newBuilder().setSourceId(source.sourceId().toString()).setTitle(source.title()).setUrl(source.url() == null ? "" : source.url()).setCitation(source.citation()).setLicenseNote(source.licenseNote())
            .setRevisionId(revision.toString()).setVersion(source.version()).setState(source.state()).setVisibility(source.visibility()).build();
        var envelope = EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli())
            .setProducer("knowledge-service").setAggregateType("Source").setAggregateId(source.sourceId().toString()).setSpaceId(source.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace)
            .setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'source',?,?,?,?,'knowledge','source-events','1',?)",
            event, source.sourceId(), type, Timestamp.from(now), trace, envelope.toByteArray());
    }
    private static void identify(UUID space, UUID source) { ConceptStore.id(space); ConceptStore.id(source); }
    private static void limit(int value, int maximum) { if (value < 1 || value > maximum) throw bad("Invalid Source page limit"); }
    private static void trace(String value) { if (value == null || !value.matches("[0-9a-f]{32}") || value.equals("0".repeat(32))) throw new IllegalArgumentException("Valid trace required"); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Private Source unavailable"); }
}
