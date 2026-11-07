package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2;
import io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2;
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

final class ClaimStore {
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record ObjectValue(String type, UUID conceptId, String value) {
        ObjectValue(String type,UUID conceptId){this(type,conceptId,null);}
    }
    enum UsageRole { OBJECT, PREDICATE }
    record Usage(Claim claim, String subjectTitle, String predicateTitle, String objectTitle) {}
    record UsagePage(List<Usage> items, UUID nextAfter) {}
    record Claim(UUID claimId, UUID spaceId, UUID subjectConceptId, UUID predicateConceptId, ObjectValue object,
                 String visibility, String state, long version, String verificationState, Instant createdAt, Instant updatedAt) {}
    record Revision(UUID revisionId, UUID claimId, UUID subjectConceptId, UUID predicateConceptId, ObjectValue object,
                    long version, String state, String verificationState, UUID actorId, Instant occurredAt) {}
    record Page(List<Claim> items, UUID nextAfter) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;

    ClaimStore(DataSource source, Clock clock) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);
        this.clock = clock;
        ids = new UuidV7(clock);
    }

    static ObjectValue object(ObjectValue value) {
        if(value==null)throw bad("Typed Claim object required");
        if("CONCEPT".equals(value.type())){ConceptStore.id(value.conceptId());if(value.value()!=null)throw bad("Concept object cannot contain literal value");return value;}
        if(value.conceptId()!=null||!Set.of("STRING","NUMBER","DATE","BOOLEAN").contains(value.type()==null?"":value.type()))throw bad("Invalid Claim object");
        var normalized=KnowledgeValue.value(value.type(),value.value());
        return new ObjectValue(normalized.type(),null,normalized.value());
    }
    private static Set<UUID> references(UUID subject,UUID predicate,ObjectValue object){
        var ids=new HashSet<>(List.of(subject,predicate));if(object.conceptId()!=null)ids.add(object.conceptId());return ids;
    }

    Claim create(UUID space, UUID subject, UUID actor, UUID predicate, ObjectValue object, String trace) {
        identify(space, subject); ConceptStore.id(actor); ConceptStore.id(predicate); var value=object(object); trace(trace);
        return transaction.execute(status -> {
            lockConcepts(space, references(subject,predicate,value));
            available(space, subject, predicate, value, null);
            UUID claim = ids.next(); Instant now = clock.instant();
            jdbc.update("INSERT INTO claim(claim_id,space_id,subject_concept_id,predicate_concept_id,object_concept_id,object_type,object_value,visibility,state,version,verification_state,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PRIVATE','ACTIVE',1,'DRAFT',?,?)",
                claim, space, subject, predicate, value.conceptId(), value.type(), value.value(), Timestamp.from(now), Timestamp.from(now));
            Claim result = load(space, subject, claim, false);
            revisionAndEvent(result, actor, trace, "ClaimCreated"); return result;
        });
    }

    Claim change(UUID space, UUID subject, UUID claim, UUID actor, long expected,
                 UUID predicate, ObjectValue object, boolean retire, String trace) {
        identify(space, subject); ConceptStore.id(claim); ConceptStore.id(actor); trace(trace);
        if (expected < 1) throw bad("Positive Claim version required");
        if (!retire) ConceptStore.id(predicate); var value=retire?null:object(object);
        return transaction.execute(status -> {
            Claim snapshot = load(space, subject, claim, false);
            if (snapshot == null || !"ACTIVE".equals(snapshot.state())) throw missing();
            var references = references(subject,snapshot.predicateConceptId(),snapshot.object());
            if (!retire) { references.add(predicate); if(value.conceptId()!=null)references.add(value.conceptId()); }
            // A global Concept lock order also covers reverse relationships and changed targets.
            lockConcepts(space, references);
            Claim prior = load(space, subject, claim, true);
            if (prior == null || !"ACTIVE".equals(prior.state())) throw missing();
            // A concurrent edit may have changed the lock set since the initial read.
            if (prior.version() != snapshot.version() || prior.version() != expected) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Claim changed; reload before saving");
            if (prior.version() == Long.MAX_VALUE) throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim version limit reached");
            UUID nextPredicate = retire ? prior.predicateConceptId() : predicate;
            ObjectValue nextObject = retire ? prior.object() : value;
            if (!retire) available(space, subject, nextPredicate, nextObject, claim);
            jdbc.update("UPDATE claim SET predicate_concept_id=?,object_concept_id=?,object_type=?,object_value=?,state=?,version=version+1,updated_at=? WHERE space_id=? AND subject_concept_id=? AND claim_id=?",
                nextPredicate, nextObject.conceptId(), nextObject.type(), nextObject.value(), retire ? "RETIRED" : "ACTIVE", Timestamp.from(clock.instant()), space, subject, claim);
            Claim result = load(space, subject, claim, false);
            revisionAndEvent(result, actor, trace, retire ? "ClaimRetired" : "ClaimUpdated"); return result;
        });
    }

    private static final String EFFECTIVE = " FROM claim k JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id JOIN concept p ON p.space_id=k.space_id AND p.concept_id=k.predicate_concept_id LEFT JOIN concept o ON o.space_id=k.space_id AND o.concept_id=k.object_concept_id WHERE k.space_id=? AND k.subject_concept_id=? AND k.state='ACTIVE' AND s.state='ACTIVE' AND s.visibility='PRIVATE' AND p.state='ACTIVE' AND p.visibility='PRIVATE' AND (k.object_type<>'CONCEPT' OR (o.state='ACTIVE' AND o.visibility='PRIVATE'))";

    Claim get(UUID space, UUID subject, UUID claim) {
        identify(space, subject); ConceptStore.id(claim);
        var rows = jdbc.query("SELECT k.*" + EFFECTIVE + " AND k.claim_id=?", (rs, n) -> read(rs), space, subject, claim);
        if (rows.isEmpty()) throw missing(); return rows.getFirst();
    }

    Page list(UUID space, UUID subject, UUID after, int limit) {
        identify(space, subject); if (after != null) ConceptStore.id(after); limit(limit, 100);
        var parents = jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND state='ACTIVE' AND visibility='PRIVATE'", UUID.class, space, subject);
        if (parents.isEmpty()) throw missing();
        String sql = "SELECT k.*" + EFFECTIVE + (after == null ? "" : " AND k.claim_id>?") + " ORDER BY k.claim_id LIMIT ?";
        Object[] args = after == null ? new Object[]{space, subject, limit + 1} : new Object[]{space, subject, after, limit + 1};
        var rows = jdbc.query(sql, (rs, n) -> read(rs), args);
        boolean more = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, more ? items.getLast().claimId() : null);
    }

    Page retired(UUID space, UUID subject, UUID after, int limit) {
        identify(space, subject); if (after != null) ConceptStore.id(after); limit(limit, 100);
        // Retirement retains private identity and history, including retired references.
        if (jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND visibility='PRIVATE'", UUID.class, space, subject).isEmpty()) throw missing();
        String sql = "SELECT * FROM claim WHERE space_id=? AND subject_concept_id=? AND visibility='PRIVATE' AND verification_state='DRAFT' AND state='RETIRED'" +
            (after == null ? "" : " AND claim_id>?") + " ORDER BY claim_id LIMIT ?";
        Object[] args = after == null ? new Object[]{space, subject, limit + 1} : new Object[]{space, subject, after, limit + 1};
        var rows = jdbc.query(sql, (rs, n) -> read(rs), args);
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, rows.size() > limit ? items.getLast().claimId() : null);
    }

    UsagePage usages(UUID space, UUID concept, UsageRole role, UUID after, int limit) {
        identify(space, concept); if (after != null) ConceptStore.id(after); limit(limit, 100);
        if (role == null) throw bad("Claim usage role required");
        // A retired private Concept remains a known identity, but cannot participate
        // in an effective active triple. Keep selected history independent of discovery.
        if (jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND visibility='PRIVATE'", UUID.class, space, concept).isEmpty()) throw missing();
        String column = role == UsageRole.OBJECT ? "object_concept_id" : "predicate_concept_id";
        String sql = "SELECT k.*,s.title AS subject_title,p.title AS predicate_title,o.title AS object_title FROM claim k " +
            "JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id " +
            "JOIN concept p ON p.space_id=k.space_id AND p.concept_id=k.predicate_concept_id " +
            "LEFT JOIN concept o ON o.space_id=k.space_id AND o.concept_id=k.object_concept_id " +
            "WHERE k.space_id=? AND k." + column + "=? AND k.visibility='PRIVATE' AND k.state='ACTIVE' " +
            "AND s.visibility='PRIVATE' AND s.state='ACTIVE' AND p.visibility='PRIVATE' AND p.state='ACTIVE' " +
            "AND (k.object_type<>'CONCEPT' OR (o.visibility='PRIVATE' AND o.state='ACTIVE'))" + (after == null ? "" : " AND k.claim_id>?") + " ORDER BY k.claim_id LIMIT ?";
        Object[] args = after == null ? new Object[]{space, concept, limit + 1} : new Object[]{space, concept, after, limit + 1};
        var rows = jdbc.query(sql, (rs, n) -> new Usage(read(rs), rs.getString("subject_title"), rs.getString("predicate_title"), rs.getString("object_type").equals("CONCEPT")?rs.getString("object_title"):rs.getString("object_value")), args);
        boolean more = rows.size() > limit; var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new UsagePage(items, more ? items.getLast().claim().claimId() : null);
    }

    List<Revision> history(UUID space, UUID subject, UUID claim, long before, int limit) {
        identify(space, subject); ConceptStore.id(claim); limit(limit, 10);
        if (before < 1) throw bad("Invalid Claim revision cursor");
        if (load(space, subject, claim, false) == null) throw missing();
        return jdbc.query("SELECT revision_id,claim_id,subject_concept_id,predicate_concept_id,object_concept_id,object_type,version,state,verification_state,actor_id,occurred_at,object_value FROM claim_revision WHERE claim_id=? AND version<? ORDER BY version DESC LIMIT ?",
            (rs, n) -> new Revision(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), new ObjectValue(rs.getString(6), rs.getObject(5, UUID.class),rs.getString(12)), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getObject(10, UUID.class), rs.getTimestamp(11).toInstant()), claim, before, limit);
    }

    private record Reference(UUID id, String state, String visibility) {}
    private void lockConcepts(UUID space, Set<UUID> references) {
        var args = new ArrayList<Object>(); args.add(space); args.addAll(references);
        var rows = jdbc.query("SELECT concept_id,state,visibility FROM concept WHERE space_id=? AND concept_id IN (" + String.join(",", Collections.nCopies(references.size(), "?")) + ") ORDER BY concept_id FOR UPDATE",
            (rs, n) -> new Reference(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), args.toArray());
        if (rows.size() != references.size() || rows.stream().anyMatch(row -> !"ACTIVE".equals(row.state()) || !"PRIVATE".equals(row.visibility()))) throw missing();
    }

    private void available(UUID space, UUID subject, UUID predicate, ObjectValue object, UUID exclude) {
        String sql="SELECT claim_id FROM claim WHERE space_id=? AND subject_concept_id=? AND predicate_concept_id=? AND object_type=? AND object_concept_id IS NOT DISTINCT FROM ?::uuid AND object_value IS NOT DISTINCT FROM ?::text AND state='ACTIVE'"+(exclude==null?"":" AND claim_id<>?");
        var args=new ArrayList<Object>();Collections.addAll(args,space,subject,predicate,object.type(),object.conceptId(),object.value());if(exclude!=null)args.add(exclude);
        if(!jdbc.queryForList(sql,UUID.class,args.toArray()).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"This typed relationship already exists");
    }

    private Claim load(UUID space, UUID subject, UUID claim, boolean lock) {
        var rows = jdbc.query("SELECT * FROM claim WHERE space_id=? AND subject_concept_id=? AND claim_id=?" + (lock ? " FOR UPDATE" : ""), (rs, n) -> read(rs), space, subject, claim);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static Claim read(ResultSet rs) throws SQLException {
        return new Claim(rs.getObject("claim_id", UUID.class), rs.getObject("space_id", UUID.class), rs.getObject("subject_concept_id", UUID.class), rs.getObject("predicate_concept_id", UUID.class), new ObjectValue(rs.getString("object_type"), rs.getObject("object_concept_id", UUID.class),rs.getString("object_value")), rs.getString("visibility"), rs.getString("state"), rs.getLong("version"), rs.getString("verification_state"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private void revisionAndEvent(Claim claim, UUID actor, String trace, String type) {
        UUID revision = ids.next(), event = ids.next(); Instant now = clock.instant();
        jdbc.update("INSERT INTO claim_revision(revision_id,claim_id,subject_concept_id,predicate_concept_id,object_concept_id,object_type,version,state,verification_state,actor_id,occurred_at,object_value) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            revision, claim.claimId(), claim.subjectConceptId(), claim.predicateConceptId(), claim.object().conceptId(), claim.object().type(), claim.version(), claim.state(), claim.verificationState(), actor, Timestamp.from(now),claim.object().value());
        var payload = ClaimChangedPayloadV2.newBuilder().setClaimId(claim.claimId().toString()).setSubjectConceptId(claim.subjectConceptId().toString())
            .setPredicateConceptId(claim.predicateConceptId().toString()).setObjectType(claim.object().type()).setObjectConceptId(claim.object().conceptId()==null?"":claim.object().conceptId().toString()).setObjectValue(claim.object().value()==null?"":claim.object().value())
            .setRevisionId(revision.toString()).setVersion(claim.version()).setState(claim.state()).setVisibility(claim.visibility()).setVerificationState(claim.verificationState()).build();
        var envelope = EventEnvelopeV2.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(2).setOccurredAtEpochMillis(now.toEpochMilli())
            .setProducer("knowledge-service").setAggregateType("Claim").setAggregateId(claim.claimId().toString()).setSpaceId(claim.spaceId().toString()).setActorId(actor.toString())
            .setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'claim-v2',?,?,?,?,'knowledge','claim-events-v2','1',?)",
            event, claim.claimId(), type, Timestamp.from(now), trace, envelope.toByteArray());
    }
    private static void identify(UUID space, UUID subject) { ConceptStore.id(space); ConceptStore.id(subject); }
    private static void limit(int value, int maximum) { if (value < 1 || value > maximum) throw bad("Invalid Claim page limit"); }
    private static void trace(String value) { if (value == null || !value.matches("[0-9a-f]{32}") || value.equals("0".repeat(32))) throw new IllegalArgumentException("Valid trace required"); }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Private Concept relationship unavailable"); }
}
