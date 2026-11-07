package io.knowledgebrain.space;

import io.knowledgebrain.contracts.space.v1.EventEnvelopeV1;
import io.knowledgebrain.contracts.space.v1.PersonalSpaceCreatedPayloadV1;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_SPACE_KAFKA_BOOTSTRAP",matches=".+")
class SpaceKafkaIntegrationTest {
    @Test void deployedConsumerCreatesPrivateSpacesAndCdcPublishesStableEventsDespiteUnacknowledgedReplay() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_SPACE_DATABASE_URL"));
        source.setUser("brain_space");source.setPassword(System.getenv("BRAIN_TEST_SPACE_DATABASE_PASSWORD"));
        var jdbc=new JdbcTemplate(source);var store=new PersonalSpaceStore(source,Clock.systemUTC());
        String group="space-validation-"+UUID.randomUUID();
        Map<UUID,GuestIdentityEvent> guests=new HashMap<>();
        try(var consumer=new KafkaConsumer<String,byte[]>(options(group))) {
            consumer.subscribe(List.of("identity.guest-identity.v1"));
            long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            long quiet=Long.MAX_VALUE;
            while(guests.size()<20 && System.nanoTime()<deadline && System.nanoTime()<quiet) {
                for(var record:consumer.poll(Duration.ofMillis(500))) {
                    var event=GuestIdentityEvent.decode(record.value(),record.key(),headers(record));guests.put(event.eventId(),event);if(guests.size()==20)break;
                    quiet=System.nanoTime()+Duration.ofSeconds(3).toNanos();
                }
            }
            assertThat(guests).isNotEmpty();
            // The application must have committed first; the validator never creates these spaces.
            for(var guest:guests.values()) {
                long ready=System.nanoTime()+Duration.ofSeconds(30).toNanos();
                while(jdbc.queryForObject("SELECT count(*) FROM event_inbox WHERE consumer_name=? AND event_id=?",Long.class,PersonalSpaceStore.CONSUMER,guest.eventId())==0 && System.nanoTime()<ready)Thread.sleep(100);
                assertThat(store.provision(guest).status()).isEqualTo(PersonalSpaceStore.Status.DUPLICATE_EVENT);
            }
            // Close with no Kafka commit, modeling the database-commit/offset-commit crash window.
        }
        Set<UUID> replayed=new HashSet<>();
        try(var replay=new KafkaConsumer<String,byte[]>(options(group))) {
            replay.subscribe(List.of("identity.guest-identity.v1"));long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
            while(!replayed.containsAll(guests.keySet()) && System.nanoTime()<deadline)for(var record:replay.poll(Duration.ofMillis(500))) {
                var guest=GuestIdentityEvent.decode(record.value(),record.key(),headers(record));
                if(guests.containsKey(guest.eventId())) {
                    assertThat(new PersonalSpaceStore(source,Clock.systemUTC()).provision(guest).status()).isEqualTo(PersonalSpaceStore.Status.DUPLICATE_EVENT);
                    replayed.add(guest.eventId());
                }
            }
        }
        assertThat(replayed).containsAll(guests.keySet());
        Map<String,byte[]> expected=new HashMap<>();
        for(var guest:guests.values()) {
            UUID space=jdbc.queryForObject("SELECT space_id FROM event_inbox WHERE consumer_name=? AND event_id=?",UUID.class,PersonalSpaceStore.CONSUMER,guest.eventId());
            assertThat(jdbc.queryForObject("SELECT visibility FROM space WHERE space_id=?",String.class,space)).isEqualTo("PRIVATE");
            assertThat(jdbc.queryForObject("SELECT role FROM space_membership WHERE space_id=? AND principal_id=?",String.class,space,guest.principalId())).isEqualTo("OWNER");
            UUID owner=jdbc.queryForObject("SELECT owner_principal_id FROM space WHERE space_id=?",UUID.class,space);
            assertThat(jdbc.queryForObject("SELECT origin_guest_principal_id FROM space WHERE space_id=?",UUID.class,space)).isEqualTo(guest.principalId());
            assertThat(store.canAccess(space,owner)).isTrue();assertThat(store.canAccess(space,guest.principalId())).isEqualTo(owner.equals(guest.principalId()));assertThat(store.canAccess(space,GuestEvents.id())).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND aggregatetype='personal-space'",Long.class,space)).isEqualTo(1);
            byte[] bytes=jdbc.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='personal-space'",byte[].class,space);
            var event=EventEnvelopeV1.parseFrom(bytes);var payload=PersonalSpaceCreatedPayloadV1.parseFrom(event.getPayload());
            assertThat(event.getTraceId()).isEqualTo(guest.traceId());assertThat(event.getEventType()).isEqualTo("PersonalSpaceCreated");
            assertThat(payload.getOwnerPrincipalId()).isEqualTo(guest.principalId().toString());assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
            expected.put(event.getEventId(),bytes);
        }
        Set<String> seen=new HashSet<>();
        try(var output=new KafkaConsumer<String,byte[]>(options("space-validation-"+UUID.randomUUID()))) {
            output.subscribe(List.of("space.personal-space.v1"));long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(!seen.containsAll(expected.keySet()) && System.nanoTime()<deadline)for(var record:output.poll(Duration.ofMillis(500))) {
                var event=EventEnvelopeV1.parseFrom(record.value());if(!expected.containsKey(event.getEventId()))continue;
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(record.key()).isEqualTo(event.getSpaceId());
                var h=headers(record);assertThat(h).containsEntry("id",event.getEventId()).containsEntry("trace_id",event.getTraceId()).containsEntry("schema_group","space").containsEntry("schema_artifact","personal-space-events").containsEntry("schema_version","1");
                seen.add(event.getEventId());
            }
        }
        assertThat(seen).containsAll(expected.keySet());
        System.out.println("PASS deployed Space consumer: PRIVATE OWNER, other-principal denial, durable Inbox replay without Kafka acknowledgement, one Outbox per Space, byte-exact CDC Protobuf and trace/schema headers; guests="+guests.size());
    }
    private Properties options(String group) throws Exception {
        var p=new Properties();p.put("bootstrap.servers",System.getenv("BRAIN_TEST_SPACE_KAFKA_BOOTSTRAP"));p.put("group.id",group);
        p.put("enable.auto.commit","false");p.put("auto.offset.reset","earliest");p.put("key.deserializer",StringDeserializer.class.getName());p.put("value.deserializer",ByteArrayDeserializer.class.getName());
        p.put("security.protocol","SSL");p.put("ssl.endpoint.identification.algorithm","HTTPS");p.put("ssl.truststore.type","PEM");p.put("ssl.keystore.type","PEM");
        p.put("ssl.truststore.certificates",Files.readString(Path.of("/space-kafka-test/ca.crt")));p.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/space-kafka-test/user.crt")));p.put("ssl.keystore.key",Files.readString(Path.of("/space-kafka-test/user.key")));return p;
    }
    private Map<String,String> headers(ConsumerRecord<String,byte[]> record) {
        Map<String,String> headers=new HashMap<>();for(var h:record.headers())headers.put(h.key(),new String(h.value(),StandardCharsets.UTF_8));return headers;
    }
}
