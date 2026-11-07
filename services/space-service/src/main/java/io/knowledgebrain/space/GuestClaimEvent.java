package io.knowledgebrain.space;

import io.knowledgebrain.contracts.identity.account.v1.*;
import java.security.MessageDigest;
import java.util.*;

record GuestClaimEvent(UUID eventId,UUID guestId,UUID ownerId,String traceId,byte[] hash) {
    GuestClaimEvent { hash=hash.clone(); }
    @Override public byte[] hash() {return hash.clone();}
    static GuestClaimEvent decode(byte[] wire,String key,Map<String,String> headers) {
        if(wire==null||wire.length==0||wire.length>65536)throw new IllegalArgumentException("Invalid claim size");
        try {
            var event=EventEnvelopeV1.parseFrom(wire);
            var payload=GuestSpaceClaimRequestedPayloadV1.parseFrom(event.getPayload());
            var id=uuid(event.getEventId());var guest=uuid(payload.getGuestPrincipalId());var owner=uuid(payload.getRegisteredPrincipalId());
            if(guest.equals(owner)||!"GuestSpaceClaimRequested".equals(event.getEventType())||event.getEventVersion()!=1||!"identity-service".equals(event.getProducer())||!"RegisteredAccount".equals(event.getAggregateType())||!owner.toString().equals(key)||!owner.toString().equals(event.getAggregateId())||!event.hasActorId()||!owner.toString().equals(event.getActorId())||event.hasSpaceId()||event.getOccurredAtEpochMillis()<=0)throw new IllegalArgumentException("Invalid claim references");
            if(!"identity".equals(headers.get("schema_group"))||!"account-events".equals(headers.get("schema_artifact"))||!"1".equals(headers.get("schema_version"))||!id.toString().equals(headers.get("id"))||!event.getTraceId().matches("[0-9a-f]{32}")||event.getTraceId().equals("0".repeat(32))||!event.getTraceId().equals(headers.get("trace_id")))throw new IllegalArgumentException("Invalid claim headers");
            return new GuestClaimEvent(id,guest,owner,event.getTraceId(),MessageDigest.getInstance("SHA-256").digest(wire));
        }catch(com.google.protobuf.InvalidProtocolBufferException e){throw new IllegalArgumentException("Malformed claim",e);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static UUID uuid(String value){var id=UUID.fromString(value);if(id.version()!=7||id.variant()!=2||!id.toString().equals(value))throw new IllegalArgumentException("UUIDv7 required");return id;}
}
