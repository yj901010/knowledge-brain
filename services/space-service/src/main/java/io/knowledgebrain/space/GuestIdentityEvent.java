package io.knowledgebrain.space;

import io.knowledgebrain.contracts.identity.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.identity.v1.GuestIdentityCreatedPayloadV1;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

record GuestIdentityEvent(UUID eventId, UUID principalId, String traceId, byte[] hash) {
    GuestIdentityEvent { hash=hash.clone(); }
    @Override public byte[] hash() { return hash.clone(); }
    static GuestIdentityEvent decode(byte[] value,String key,Map<String,String> headers) {
        if(value==null || value.length==0 || value.length>65536)throw new IllegalArgumentException("Invalid event size");
        if(!"identity".equals(headers.get("schema_group")) || !"guest-identity-events".equals(headers.get("schema_artifact")) || !"1".equals(headers.get("schema_version")))
            throw new IllegalArgumentException("Unsupported Identity schema");
        byte[] wire=value.clone();
        try {
            var event=EventEnvelopeV1.parseFrom(wire);
            var payload=GuestIdentityCreatedPayloadV1.parseFrom(event.getPayload());
            UUID id=uuid(event.getEventId()),principal=uuid(payload.getPrincipalId());
            if(!"GuestIdentityCreated".equals(event.getEventType()) || event.getEventVersion()!=1 || !"identity-service".equals(event.getProducer()) || !"GuestIdentity".equals(event.getAggregateType()))
                throw new IllegalArgumentException("Unsupported Identity event");
            if(!principal.toString().equals(event.getAggregateId()) || !principal.toString().equals(key) || !event.hasActorId() || !principal.toString().equals(event.getActorId()) || event.hasSpaceId() || event.getOccurredAtEpochMillis()<=0)
                throw new IllegalArgumentException("Inconsistent guest identity references");
            String trace=event.getTraceId();
            if(!trace.matches("[0-9a-f]{32}") || trace.equals("0".repeat(32)) || !trace.equals(headers.get("trace_id")) || !id.toString().equals(headers.get("id")))
                throw new IllegalArgumentException("Invalid event/trace headers");
            return new GuestIdentityEvent(id,principal,trace,MessageDigest.getInstance("SHA-256").digest(wire));
        } catch(com.google.protobuf.InvalidProtocolBufferException error) { throw new IllegalArgumentException("Malformed Protobuf event",error); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static UUID uuid(String value) {
        UUID id=UUID.fromString(value);
        if(id.version()!=7 || id.variant()!=2 || !id.toString().equals(value))throw new IllegalArgumentException("Canonical UUIDv7 required");
        return id;
    }
}
