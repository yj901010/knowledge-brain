package io.knowledgebrain.space;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.space.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.space.v1.PersonalSpaceCreatedPayloadV1;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class PersonalSpaceStore {
    static final String CONSUMER="guest-personal-space-v1";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;
    PersonalSpaceStore(DataSource source,Clock clock) {
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);
    }
    enum Status { CREATED, DUPLICATE_EVENT, EXISTING_PERSONAL_SPACE }
    record Outcome(Status status,UUID spaceId) {}
    Outcome provision(GuestIdentityEvent source) {
        UUID candidate=ids.next();var now=clock.instant();
        return transaction.execute(status->{
            lockGuest(source.principalId());
            int inserted=jdbc.update("INSERT INTO event_inbox(consumer_name,event_id,payload_hash,space_id,processed_at,trace_id) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                CONSUMER,source.eventId(),source.hash(),candidate,Timestamp.from(now),source.traceId());
            if(inserted==0) {
                var prior=jdbc.queryForMap("SELECT payload_hash,space_id FROM event_inbox WHERE consumer_name=? AND event_id=?",CONSUMER,source.eventId());
                if(!MessageDigest.isEqual((byte[])prior.get("payload_hash"),source.hash()))throw new IllegalStateException("Event ID reused with different content");
                return new Outcome(Status.DUPLICATE_EVENT,(UUID)prior.get("space_id"));
            }
            var created=jdbc.query("INSERT INTO space(space_id,kind,visibility,owner_principal_id,created_at,origin_guest_principal_id) VALUES (?,'PERSONAL','PRIVATE',?,?,?) ON CONFLICT (origin_guest_principal_id) WHERE kind='PERSONAL' DO NOTHING RETURNING space_id",
                (rs,n)->rs.getObject(1,UUID.class),candidate,source.principalId(),Timestamp.from(now),source.principalId());
            if(created.isEmpty()) {
                UUID existing=jdbc.queryForObject("SELECT space_id FROM space WHERE origin_guest_principal_id=? AND kind='PERSONAL'",UUID.class,source.principalId());
                jdbc.update("UPDATE event_inbox SET space_id=? WHERE consumer_name=? AND event_id=?",existing,CONSUMER,source.eventId());
                return new Outcome(Status.EXISTING_PERSONAL_SPACE,existing);
            }
            jdbc.update("INSERT INTO space_membership(space_id,principal_id,role) VALUES (?,?,'OWNER')",candidate,source.principalId());
            UUID eventId=ids.next();
            var payload=PersonalSpaceCreatedPayloadV1.newBuilder().setSpaceId(candidate.toString()).setOwnerPrincipalId(source.principalId().toString()).setVisibility("PRIVATE").build();
            var event=EventEnvelopeV1.newBuilder().setEventId(eventId.toString()).setEventType("PersonalSpaceCreated").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli())
                .setProducer("space-service").setAggregateType("Space").setAggregateId(candidate.toString()).setSpaceId(candidate.toString()).setActorId(source.principalId().toString())
                .setTraceId(source.traceId()).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
            jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'personal-space',?,'PersonalSpaceCreated',?,?,'space','personal-space-events','1',?)",
                eventId,candidate,Timestamp.from(now),source.traceId(),event.toByteArray());
            applyClaim(source.principalId());
            return new Outcome(Status.CREATED,candidate);
        });
    }
    private void lockGuest(UUID guest){jdbc.query("SELECT pg_advisory_xact_lock(?)",(rs,n)->0,guest.getMostSignificantBits()^guest.getLeastSignificantBits());}
    void claim(GuestClaimEvent event) {
        transaction.executeWithoutResult(status->{
            lockGuest(event.guestId());
            int created=jdbc.update("INSERT INTO guest_space_claim(guest_principal_id,registered_principal_id,event_id,payload_hash,trace_id,claimed_at) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",event.guestId(),event.ownerId(),event.eventId(),event.hash(),event.traceId(),Timestamp.from(clock.instant()));
            if(created==0){var prior=jdbc.queryForMap("SELECT registered_principal_id,event_id,payload_hash FROM guest_space_claim WHERE guest_principal_id=?",event.guestId());if(!event.ownerId().equals(prior.get("registered_principal_id"))||!event.eventId().equals(prior.get("event_id"))||!MessageDigest.isEqual(event.hash(),(byte[])prior.get("payload_hash")))throw new IllegalStateException("Conflicting guest claim");return;}
            applyClaim(event.guestId());
        });
    }
    private void applyClaim(UUID guest) {
        var claims=jdbc.queryForList("SELECT * FROM guest_space_claim WHERE guest_principal_id=?",guest);
        if(claims.isEmpty())return;var claim=claims.getFirst();UUID owner=(UUID)claim.get("registered_principal_id");
        var spaces=jdbc.queryForList("SELECT space_id,owner_principal_id FROM space WHERE origin_guest_principal_id=? AND kind='PERSONAL' AND visibility='PRIVATE' FOR UPDATE",guest);
        if(spaces.isEmpty())return;var space=spaces.getFirst();UUID id=(UUID)space.get("space_id");
        if(owner.equals(space.get("owner_principal_id")))return;
        if(!guest.equals(space.get("owner_principal_id")))throw new IllegalStateException("Space already claimed");
        jdbc.update("UPDATE space SET owner_principal_id=? WHERE space_id=?",owner,id);
        jdbc.update("UPDATE space_membership SET state='RETIRED' WHERE space_id=? AND principal_id=?",id,guest);
        jdbc.update("INSERT INTO space_membership(space_id,principal_id,role) VALUES (?,?,'OWNER') ON CONFLICT(space_id,principal_id) DO UPDATE SET role='OWNER',state='ACTIVE'",id,owner);
        var now=clock.instant();UUID eventId=ids.next();String trace=(String)claim.get("trace_id");
        var payload=io.knowledgebrain.contracts.space.claim.v1.PersonalSpaceClaimedPayloadV1.newBuilder().setSpaceId(id.toString()).setGuestPrincipalId(guest.toString()).setOwnerPrincipalId(owner.toString()).build();
        var event=io.knowledgebrain.contracts.space.claim.v1.EventEnvelopeV1.newBuilder().setEventId(eventId.toString()).setEventType("PersonalSpaceClaimed").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("space-service").setAggregateType("Space").setAggregateId(id.toString()).setSpaceId(id.toString()).setActorId(owner.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'personal-space-claim',?,'PersonalSpaceClaimed',?,?,'space','personal-space-claim-events','1',?)",eventId,id,Timestamp.from(now),trace,event.toByteArray());
    }
    List<SpaceView> owned(UUID principal) {
        return jdbc.query("SELECT s.space_id,s.kind,s.visibility,m.role FROM space s JOIN space_membership m ON m.space_id=s.space_id WHERE s.owner_principal_id=? AND s.kind='PERSONAL' AND s.state='ACTIVE' AND m.principal_id=? AND m.state='ACTIVE' ORDER BY s.space_id DESC LIMIT 100",(rs,n)->new SpaceView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),principal,principal);
    }
    SpaceView claimed(UUID guest,UUID owner) {
        var rows=jdbc.query("SELECT s.space_id,s.kind,s.visibility,m.role FROM space s JOIN space_membership m ON m.space_id=s.space_id WHERE s.origin_guest_principal_id=? AND s.owner_principal_id=? AND m.principal_id=? AND s.kind='PERSONAL' AND s.state='ACTIVE' AND m.state='ACTIVE'",(rs,n)->new SpaceView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),guest,owner,owner);
        return rows.isEmpty()?null:rows.getFirst();
    }
    boolean canAccess(UUID spaceId,UUID principalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM space s JOIN space_membership m ON m.space_id=s.space_id WHERE s.space_id=? AND m.principal_id=? AND s.state='ACTIVE' AND m.state='ACTIVE')",Boolean.class,spaceId,principalId));
    }
    record SpaceView(UUID spaceId,String kind,String visibility,String role) {}
    SpaceView personal(UUID principalId) {
        var rows=jdbc.query("SELECT s.space_id,s.kind,s.visibility,m.role FROM space s JOIN space_membership m ON m.space_id=s.space_id WHERE s.owner_principal_id=? AND s.kind='PERSONAL' AND m.principal_id=? AND s.state='ACTIVE' AND m.state='ACTIVE' ORDER BY COALESCE((SELECT claimed_at FROM guest_space_claim c WHERE c.guest_principal_id=s.origin_guest_principal_id),s.created_at) DESC,s.space_id DESC",
            (rs,n)->new SpaceView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),principalId,principalId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    SpaceView visible(UUID spaceId,UUID principalId) {
        var rows=jdbc.query("SELECT s.space_id,s.kind,s.visibility,m.role FROM space s JOIN space_membership m ON m.space_id=s.space_id WHERE s.space_id=? AND m.principal_id=? AND s.state='ACTIVE' AND m.state='ACTIVE'",
            (rs,n)->new SpaceView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4)),spaceId,principalId);
        return rows.isEmpty()?null:rows.getFirst();
    }
}
