package io.knowledgebrain.identity;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.identity.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.identity.v1.GuestIdentityCreatedPayloadV1;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class GuestRegistrationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;
    public GuestRegistrationStore(DataSource source, Clock clock) {
        this.jdbc = new JdbcTemplate(source);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.clock = clock;
        this.ids = new UuidV7(clock);
    }
    public record CreatedGuest(UUID principalId, UUID eventId) {}
    public CreatedGuest register(byte[] credentialHash, String traceId) {
        if (credentialHash == null || credentialHash.length != 32) throw new IllegalArgumentException("SHA-256 credential hash required");
        if (traceId == null || !traceId.matches("[0-9a-f]{32}") || traceId.equals("0".repeat(32))) throw new IllegalArgumentException("Nonzero W3C trace ID required");
        byte[] hash = credentialHash.clone();
        UUID principalId = ids.next();
        UUID eventId = ids.next();
        var occurredAt = clock.instant();
        var payload = GuestIdentityCreatedPayloadV1.newBuilder().setPrincipalId(principalId.toString()).build();
        var envelope = EventEnvelopeV1.newBuilder().setEventId(eventId.toString())
                .setEventType("GuestIdentityCreated").setEventVersion(1)
                .setOccurredAtEpochMillis(occurredAt.toEpochMilli()).setProducer("identity-service")
                .setAggregateType("GuestIdentity").setAggregateId(principalId.toString())
                .setActorId(principalId.toString()).setTraceId(traceId)
                .setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO guest_identity(principal_id,credential_hash,created_at,credential_expires_at) VALUES (?,?,?,?)", principalId,hash,Timestamp.from(occurredAt),Timestamp.from(occurredAt.plus(java.time.Duration.ofDays(180))));
            jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    eventId,"guest-identity",principalId,"GuestIdentityCreated",Timestamp.from(occurredAt),traceId,"identity","guest-identity-events","1",envelope.toByteArray());
            return new CreatedGuest(principalId,eventId);
        });
    }
}
