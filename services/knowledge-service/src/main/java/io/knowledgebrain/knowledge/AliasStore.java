package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.alias.v1.AliasChangedPayloadV1;
import io.knowledgebrain.contracts.knowledge.alias.v1.EventEnvelopeV1;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

final class AliasStore {
    record Alias(UUID aliasId, UUID conceptId, UUID spaceId, String visibility, String scope,
                 String state, long version, String label, Instant createdAt, Instant updatedAt) {}
    record Revision(UUID revisionId, UUID aliasId, long version, String state, String label,
                    UUID actorId, Instant occurredAt) {}
    record Page(List<Alias> items, UUID nextAfter) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;

    AliasStore(DataSource source, Clock clock) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);
        this.clock = clock;
        ids = new UuidV7(clock);
    }

    static String label(String value) {
        if (value == null) throw bad("Alias label required");
        String clean = value.strip();
        if (clean.isBlank() || clean.codePointCount(0, clean.length()) > 200 || clean.indexOf(0) >= 0)
            throw bad("Alias label must contain 1 to 200 characters");
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == clean.length() || !Character.isLowSurrogate(clean.charAt(i))) throw bad("Invalid Alias text");
            } else if (Character.isLowSurrogate(c)) throw bad("Invalid Alias text");
        }
        return clean;
    }

    Alias create(UUID space, UUID concept, UUID actor, String text, String trace) {
        identify(space, concept); ConceptStore.id(actor); trace(trace);
        String clean = label(text);
        return transaction.execute(status -> {
            parent(space, concept, true);
            available(space, concept, null, clean);
            int count = jdbc.queryForObject("SELECT count(*) FROM alias WHERE space_id=? AND concept_id=? AND state='ACTIVE'", Integer.class, space, concept);
            if (count >= 100) throw new ResponseStatusException(HttpStatus.CONFLICT, "Concept Alias limit reached");
            UUID alias = ids.next(); Instant now = clock.instant();
            jdbc.update("INSERT INTO alias(alias_id,concept_id,space_id,visibility,scope,state,version,label,created_at,updated_at) VALUES (?,?,?,'PRIVATE','SPACE','ACTIVE',1,?,?,?)",
                alias, concept, space, clean, Timestamp.from(now), Timestamp.from(now));
            Alias result = load(space, concept, alias, false);
            revisionAndEvent(result, actor, trace, "AliasCreated");
            return result;
        });
    }

    Alias change(UUID space, UUID concept, UUID alias, UUID actor, long expected,
                 String text, boolean retire, String trace) {
        identify(space, concept); ConceptStore.id(alias); ConceptStore.id(actor); trace(trace);
        if (expected < 1) throw bad("Positive Alias version required");
        String clean = retire ? null : label(text);
        return transaction.execute(status -> {
            // The same parent lock serializes Alias writes with Concept retirement.
            parent(space, concept, true);
            Alias prior = load(space, concept, alias, true);
            if (prior == null || !"ACTIVE".equals(prior.state())) throw missing();
            if (prior.version() != expected) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Alias changed; reload before saving");
            if (prior.version() == Long.MAX_VALUE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Alias version limit reached");
            if (!retire) available(space, concept, alias, clean);
            jdbc.update("UPDATE alias SET label=?,state=?,version=version+1,updated_at=? WHERE space_id=? AND concept_id=? AND alias_id=?",
                retire ? prior.label() : clean, retire ? "RETIRED" : "ACTIVE", Timestamp.from(clock.instant()), space, concept, alias);
            Alias result = load(space, concept, alias, false);
            revisionAndEvent(result, actor, trace, retire ? "AliasRetired" : "AliasUpdated");
            return result;
        });
    }

    Alias get(UUID space, UUID concept, UUID alias) {
        identify(space, concept); ConceptStore.id(alias);
        var rows = jdbc.query("SELECT a.* FROM alias a JOIN concept c ON c.space_id=a.space_id AND c.concept_id=a.concept_id WHERE a.space_id=? AND a.concept_id=? AND a.alias_id=? AND a.state='ACTIVE' AND c.state='ACTIVE' AND c.visibility='PRIVATE'",
            (rs, n) -> read(rs), space, concept, alias);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    Page list(UUID space, UUID concept, UUID after, int limit) {
        identify(space, concept); if (after != null) ConceptStore.id(after); limit(limit, 100);
        parent(space, concept, false);
        String sql = "SELECT a.* FROM alias a JOIN concept c ON c.space_id=a.space_id AND c.concept_id=a.concept_id WHERE a.space_id=? AND a.concept_id=? AND a.state='ACTIVE' AND c.state='ACTIVE' AND c.visibility='PRIVATE'"
            + (after == null ? "" : " AND a.alias_id>?") + " ORDER BY a.alias_id LIMIT ?";
        Object[] args = after == null ? new Object[]{space, concept, limit + 1} : new Object[]{space, concept, after, limit + 1};
        var rows = jdbc.query(sql, (rs, n) -> read(rs), args);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, more ? items.getLast().aliasId() : null);
    }

    Page retired(UUID space, UUID concept, UUID after, int limit) {
        identify(space, concept); if (after != null) ConceptStore.id(after); limit(limit, 100);
        if (jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND visibility='PRIVATE'", UUID.class, space, concept).isEmpty()) throw missing();
        String sql = "SELECT a.* FROM alias a WHERE a.space_id=? AND a.concept_id=? AND a.visibility='PRIVATE' AND a.scope='SPACE' AND a.state='RETIRED'"
            + (after == null ? "" : " AND a.alias_id>?") + " ORDER BY a.alias_id LIMIT ?";
        Object[] args = after == null ? new Object[]{space, concept, limit + 1} : new Object[]{space, concept, after, limit + 1};
        var rows = jdbc.query(sql, (rs, n) -> read(rs), args);
        boolean more = rows.size() > limit; var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, more ? items.getLast().aliasId() : null);
    }
    List<Revision> history(UUID space, UUID concept, UUID alias, long before, int limit) {
        identify(space, concept); ConceptStore.id(alias); limit(limit, 10);
        if (before < 1) throw bad("Invalid Alias revision cursor");
        var present = jdbc.queryForList("SELECT a.alias_id FROM alias a JOIN concept c ON c.space_id=a.space_id AND c.concept_id=a.concept_id WHERE a.space_id=? AND a.concept_id=? AND a.alias_id=? AND c.visibility='PRIVATE'", UUID.class, space, concept, alias);
        if (present.isEmpty()) throw missing();
        return jdbc.query("SELECT revision_id,alias_id,version,state,label,actor_id,occurred_at FROM alias_revision WHERE alias_id=? AND version<? ORDER BY version DESC LIMIT ?",
            (rs, n) -> new Revision(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getTimestamp(7).toInstant()), alias, before, limit);
    }

    private void parent(UUID space, UUID concept, boolean lock) {
        var rows = jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND visibility='PRIVATE' AND state='ACTIVE'" + (lock ? " FOR UPDATE" : ""), UUID.class, space, concept);
        if (rows.isEmpty()) throw missing();
    }

    private void available(UUID space, UUID concept, UUID exclude, String text) {
        String sql = "SELECT alias_id FROM alias WHERE space_id=? AND concept_id=? AND label=? AND state='ACTIVE'" + (exclude == null ? "" : " AND alias_id<>?");
        Object[] args = exclude == null ? new Object[]{space, concept, text} : new Object[]{space, concept, text, exclude};
        if (!jdbc.queryForList(sql, UUID.class, args).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Alias label already exists on this Concept");
    }

    private Alias load(UUID space, UUID concept, UUID alias, boolean lock) {
        var rows = jdbc.query("SELECT * FROM alias WHERE space_id=? AND concept_id=? AND alias_id=?" + (lock ? " FOR UPDATE" : ""), (rs, n) -> read(rs), space, concept, alias);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Alias read(ResultSet rs) throws SQLException {
        return new Alias(rs.getObject("alias_id", UUID.class), rs.getObject("concept_id", UUID.class), rs.getObject("space_id", UUID.class), rs.getString("visibility"), rs.getString("scope"), rs.getString("state"), rs.getLong("version"), rs.getString("label"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private void revisionAndEvent(Alias alias, UUID actor, String trace, String type) {
        UUID revision = ids.next(), event = ids.next(); Instant now = clock.instant();
        jdbc.update("INSERT INTO alias_revision(revision_id,alias_id,version,state,label,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?)",
            revision, alias.aliasId(), alias.version(), alias.state(), alias.label(), actor, Timestamp.from(now));
        var payload = AliasChangedPayloadV1.newBuilder().setAliasId(alias.aliasId().toString()).setConceptId(alias.conceptId().toString())
            .setRevisionId(revision.toString()).setVersion(alias.version()).setState(alias.state()).setVisibility(alias.visibility()).setScope(alias.scope()).setLabel(alias.label()).build();
        var envelope = EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli())
            .setProducer("knowledge-service").setAggregateType("Alias").setAggregateId(alias.aliasId().toString()).setSpaceId(alias.spaceId().toString()).setActorId(actor.toString())
            .setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'alias',?,?,?,?,'knowledge','alias-events','1',?)",
            event, alias.aliasId(), type, Timestamp.from(now), trace, envelope.toByteArray());
    }

    private static void identify(UUID space, UUID concept) { ConceptStore.id(space); ConceptStore.id(concept); }
    private static void limit(int value, int maximum) { if (value < 1 || value > maximum) throw bad("Invalid Alias page limit"); }
    private static void trace(String value) { if (value == null || !value.matches("[0-9a-f]{32}") || value.equals("0".repeat(32))) throw new IllegalArgumentException("Valid trace required"); }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Concept Alias unavailable"); }
}
