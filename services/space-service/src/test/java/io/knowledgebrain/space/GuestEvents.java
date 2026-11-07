package io.knowledgebrain.space;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.identity.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.identity.v1.GuestIdentityCreatedPayloadV1;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
final class GuestEvents {
    static final String TRACE="0123456789abcdef0123456789abcdef";
    static UUID id() { return new UuidV7(Clock.systemUTC()).next(); }
    static EventEnvelopeV1 envelope(UUID eventId,UUID principal,String trace) {
        return EventEnvelopeV1.newBuilder().setEventId(eventId.toString()).setEventType("GuestIdentityCreated").setEventVersion(1)
            .setOccurredAtEpochMillis(Clock.systemUTC().millis()).setProducer("identity-service").setAggregateType("GuestIdentity")
            .setAggregateId(principal.toString()).setActorId(principal.toString()).setTraceId(trace)
            .setPayload(ByteString.copyFrom(GuestIdentityCreatedPayloadV1.newBuilder().setPrincipalId(principal.toString()).build().toByteArray())).build();
    }
    static Map<String,String> headers(EventEnvelopeV1 event) { return Map.of("schema_group","identity","schema_artifact","guest-identity-events","schema_version","1","trace_id",event.getTraceId(),"id",event.getEventId()); }
    static GuestIdentityEvent event(UUID eventId,UUID principal,String trace) {
        var event=envelope(eventId,principal,trace);return GuestIdentityEvent.decode(event.toByteArray(),principal.toString(),headers(event));
    }
}
