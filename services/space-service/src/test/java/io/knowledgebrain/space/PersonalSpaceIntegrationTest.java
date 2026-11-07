package io.knowledgebrain.space;

import io.knowledgebrain.contracts.space.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.space.v1.PersonalSpaceCreatedPayloadV1;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_SPACE_DATABASE_URL",matches=".+")
class PersonalSpaceIntegrationTest {
    private PGSimpleDataSource source;
    private JdbcTemplate jdbc;
    private PersonalSpaceStore store;
    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_SPACE_DATABASE_URL"));
        source.setUser("brain_space");source.setPassword(System.getenv("BRAIN_TEST_SPACE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);store=new PersonalSpaceStore(source,Clock.systemUTC());
    }
    @Test void createsPrivateSpaceOwnerAndTypedOutboxAndDeniesOtherPrincipal() throws Exception {
        var event=GuestEvents.event(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);
        var created=store.provision(event);assertThat(created.status()).isEqualTo(PersonalSpaceStore.Status.CREATED);
        assertThat(created.spaceId().version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT visibility FROM space WHERE space_id=?",String.class,created.spaceId())).isEqualTo("PRIVATE");
        assertThat(jdbc.queryForObject("SELECT role FROM space_membership WHERE space_id=? AND principal_id=?",String.class,created.spaceId(),event.principalId())).isEqualTo("OWNER");
        assertThat(store.canAccess(created.spaceId(),event.principalId())).isTrue();
        assertThat(store.canAccess(created.spaceId(),GuestEvents.id())).isFalse();
        var envelope=EventEnvelopeV1.parseFrom(jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,created.spaceId()));
        assertThat(UUID.fromString(envelope.getEventId()).version()).isEqualTo(7);
        assertThat(envelope.getSpaceId()).isEqualTo(created.spaceId().toString());assertThat(envelope.getTraceId()).isEqualTo(event.traceId());
        var payload=PersonalSpaceCreatedPayloadV1.parseFrom(envelope.getPayload());
        assertThat(payload.getSpaceId()).isEqualTo(created.spaceId().toString());assertThat(payload.getOwnerPrincipalId()).isEqualTo(event.principalId().toString());assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
        assertThatThrownBy(()->jdbc.update("UPDATE space SET visibility='PUBLIC' WHERE space_id=?",created.spaceId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void replaysAfterStoreRestartWithoutNewSpaceMembershipOrOutbox() {
        var event=GuestEvents.event(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);var first=store.provision(event);
        var replay=new PersonalSpaceStore(source,Clock.systemUTC()).provision(event);
        assertThat(replay.status()).isEqualTo(PersonalSpaceStore.Status.DUPLICATE_EVENT);assertThat(replay.spaceId()).isEqualTo(first.spaceId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Long.class,first.spaceId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_membership WHERE space_id=?",Long.class,first.spaceId())).isEqualTo(1);
    }
    @Test void concurrentRedeliveryCreatesExactlyOneSpaceAndEvent() throws Exception {
        var event=GuestEvents.event(GuestEvents.id(),GuestEvents.id(),GuestEvents.TRACE);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=IntStream.range(0,6).mapToObj(i->executor.submit(()->store.provision(event))).toList();
            var results=new java.util.ArrayList<PersonalSpaceStore.Outcome>();
            for(var task:tasks)results.add(task.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(r->r.status()==PersonalSpaceStore.Status.CREATED).count()).isEqualTo(1);
            assertThat(results.stream().map(PersonalSpaceStore.Outcome::spaceId).distinct().count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Long.class,results.getFirst().spaceId())).isEqualTo(1);
        }
    }
    @Test void differentEventForSamePrincipalRetainsSinglePersonalSpace() {
        UUID principal=GuestEvents.id();var first=store.provision(GuestEvents.event(GuestEvents.id(),principal,GuestEvents.TRACE));
        var second=store.provision(GuestEvents.event(GuestEvents.id(),principal,GuestEvents.TRACE));
        assertThat(second.status()).isEqualTo(PersonalSpaceStore.Status.EXISTING_PERSONAL_SPACE);assertThat(second.spaceId()).isEqualTo(first.spaceId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Long.class,first.spaceId())).isEqualTo(1);
    }
    @Test void outboxFailureRollsBackInboxSpaceAndMembershipThenRetrySucceeds() {
        var event=GuestEvents.event(GuestEvents.id(),GuestEvents.id(),"b".repeat(32));
        jdbc.execute("ALTER TABLE outbox_event ADD CONSTRAINT validation_reject_space_trace CHECK (trace_id <> 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb') NOT VALID");
        try {
            assertThatThrownBy(()->store.provision(event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE event_id=?",Long.class,event.eventId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM space WHERE owner_principal_id=?",Long.class,event.principalId())).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM space_membership WHERE principal_id=?",Long.class,event.principalId())).isZero();
        } finally { jdbc.execute("ALTER TABLE outbox_event DROP CONSTRAINT validation_reject_space_trace"); }
        assertThat(store.provision(event).status()).isEqualTo(PersonalSpaceStore.Status.CREATED);
    }
    @Test void rejectsChangedPayloadWithSameEventIdWithoutProvisioningAnotherOwner() {
        UUID id=GuestEvents.id();var first=GuestEvents.event(id,GuestEvents.id(),GuestEvents.TRACE);store.provision(first);
        var forged=GuestEvents.event(id,GuestEvents.id(),GuestEvents.TRACE);
        assertThatThrownBy(()->store.provision(forged)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space WHERE owner_principal_id=?",Long.class,forged.principalId())).isZero();
    }
}
