package io.knowledgebrain.space;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GuestIdentityEventTest {
    @Test void acceptsExactContractAndProtectsHashFromMutation() {
        var raw=GuestEvents.envelope(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);
        var event=GuestIdentityEvent.decode(raw.toByteArray(),raw.getAggregateId(),GuestEvents.headers(raw));
        byte[] hash=event.hash();hash[0]^=1;assertThat(event.hash()).isNotEqualTo(hash);
        assertThat(event.principalId().toString()).isEqualTo(raw.getAggregateId());
    }
    @Test void rejectsUnsupportedProducerVersionAndSchema() {
        var raw=GuestEvents.envelope(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);
        for(var bad:new io.knowledgebrain.contracts.identity.v1.EventEnvelopeV1[]{raw.toBuilder().setProducer("other-service").build(),raw.toBuilder().setEventVersion(2).build()})
            assertThatThrownBy(()->GuestIdentityEvent.decode(bad.toByteArray(),bad.getAggregateId(),GuestEvents.headers(bad))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GuestIdentityEvent.decode(raw.toByteArray(),raw.getAggregateId(),Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsSpoofedActorKeyTraceAndNonV7Identity() {
        var raw=GuestEvents.envelope(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);
        var actor=raw.toBuilder().setActorId(GuestEvents.id().toString()).build();
        assertThatThrownBy(()->GuestIdentityEvent.decode(actor.toByteArray(),actor.getAggregateId(),GuestEvents.headers(actor))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GuestIdentityEvent.decode(raw.toByteArray(),GuestEvents.id().toString(),GuestEvents.headers(raw))).isInstanceOf(IllegalArgumentException.class);
        var zero=raw.toBuilder().setTraceId("0".repeat(32)).build();
        assertThatThrownBy(()->GuestIdentityEvent.decode(zero.toByteArray(),zero.getAggregateId(),GuestEvents.headers(zero))).isInstanceOf(IllegalArgumentException.class);
        var v4=GuestEvents.envelope(java.util.UUID.randomUUID(),GuestEvents.id(),GuestEvents.TRACE);
        assertThatThrownBy(()->GuestIdentityEvent.decode(v4.toByteArray(),v4.getAggregateId(),GuestEvents.headers(v4))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsTombstoneMalformedAndOversizedRecords() {
        assertThatThrownBy(()->GuestIdentityEvent.decode(null,"",Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->GuestIdentityEvent.decode(new byte[65537],"",Map.of())).isInstanceOf(IllegalArgumentException.class);
        var raw=GuestEvents.envelope(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);
        assertThatThrownBy(()->GuestIdentityEvent.decode(new byte[]{(byte)255},raw.getAggregateId(),GuestEvents.headers(raw))).isInstanceOf(IllegalArgumentException.class);
    }
}
