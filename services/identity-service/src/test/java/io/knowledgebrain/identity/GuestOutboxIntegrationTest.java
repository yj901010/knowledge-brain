package io.knowledgebrain.identity;
import io.knowledgebrain.contracts.identity.v1.*;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_DATABASE_URL",matches=".+")
class GuestOutboxIntegrationTest {
    private JdbcTemplate jdbc;
    private GuestRegistrationStore store;
    @BeforeEach void prepare() {
        var source=new PGSimpleDataSource();
        source.setURL(System.getenv("BRAIN_TEST_DATABASE_URL"));
        source.setUser("brain_identity");source.setPassword(System.getenv("BRAIN_TEST_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);store=new GuestRegistrationStore(source,Clock.systemUTC());
    }
    @Test void commitsCompleteProtobufAndRollsBackDomainWriteWhenOutboxFails() throws Exception {
        byte[] hash=new byte[32];new java.security.SecureRandom().nextBytes(hash);
        var trace="0123456789abcdef0123456789abcdef";
        var created=store.register(hash,trace);
        var bytes=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE id=?",byte[].class,created.eventId());
        var envelope=EventEnvelopeV1.parseFrom(bytes);
        assertThat(envelope.getEventId()).isEqualTo(created.eventId().toString());
        assertThat(envelope.getAggregateId()).isEqualTo(created.principalId().toString());
        assertThat(envelope.getTraceId()).isEqualTo(trace);
        assertThat(envelope.hasSpaceId()).isFalse();
        assertThat(GuestIdentityCreatedPayloadV1.parseFrom(envelope.getPayload()).getPrincipalId()).isEqualTo(created.principalId().toString());
        assertThat(created.eventId().version()).isEqualTo(7);
        assertThat(created.principalId().version()).isEqualTo(7);
        long guests=jdbc.queryForObject("SELECT count(*) FROM guest_identity",Long.class);
        long events=jdbc.queryForObject("SELECT count(*) FROM outbox_event",Long.class);
        assertThatThrownBy(()->store.register(hash,trace)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event",Long.class)).isEqualTo(events);
        // Fail only this test event after its guest INSERT; preserve existing history.
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_reject_trace CHECK (trace_id <> 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa') NOT VALID");
        try {
            byte[] other=hash.clone();other[0]^=1;
            assertThatThrownBy(()->store.register(other,"a".repeat(32))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_identity",Long.class)).isEqualTo(guests);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event",Long.class)).isEqualTo(events);
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_reject_trace");  }
        System.out.println("VALIDATED_EVENT_ID="+created.eventId());
    }
}
