package io.knowledgebrain.identity;

import io.knowledgebrain.contracts.identity.v1.EventEnvelopeV1;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KAFKA_BOOTSTRAP",matches=".+")
class KafkaOutboxIntegrationTest {
    @Test void streamsCommittedBytesOmitsRollbackAndReplaysStableEventIds() throws Exception {
        var source=new PGSimpleDataSource();
        source.setURL(System.getenv("BRAIN_TEST_DATABASE_URL"));
        source.setUser("brain_identity");source.setPassword(System.getenv("BRAIN_TEST_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var jdbc=new JdbcTemplate(source);
        var store=new GuestRegistrationStore(source,Clock.systemUTC());
        String trace="0123456789abcdef0123456789abcdef";
        byte[] committedHash=new byte[32],abortedHash=new byte[32];
        new SecureRandom().nextBytes(committedHash);new SecureRandom().nextBytes(abortedHash);
        var committed=store.register(committedHash,trace);
        var aborted=new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            var created=store.register(abortedHash,trace);status.setRollbackOnly();return created;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE id=?",Long.class,aborted.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM guest_identity WHERE principal_id=?",Long.class,aborted.principalId())).isZero();
        Map<String,byte[]> expected=new HashMap<>();
        for(var row:jdbc.query("SELECT id,payload FROM outbox_event WHERE aggregatetype='guest-identity'",(rs,n)->Map.entry(rs.getString(1),rs.getBytes(2))))expected.put(row.getKey(),row.getValue());
        Properties options=new Properties();
        options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KAFKA_BOOTSTRAP"));
        options.put("group.id","brain-cdc-validation-"+UUID.randomUUID());
        options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/kafka-test/user.key")));
        var deniedWriteOptions=new Properties();deniedWriteOptions.putAll(options);
        deniedWriteOptions.put("key.serializer",StringSerializer.class.getName());
        deniedWriteOptions.put("value.serializer",ByteArraySerializer.class.getName());
        deniedWriteOptions.put("max.block.ms","5000");deniedWriteOptions.put("delivery.timeout.ms","10000");
        deniedWriteOptions.put("request.timeout.ms","5000");deniedWriteOptions.put("retries","0");
        // No idempotent-write cluster permission is needed to prove topic-level denial.
        deniedWriteOptions.put("enable.idempotence","false");
        try(var producer=new KafkaProducer<String,byte[]>(deniedWriteOptions)) {
            assertThatThrownBy(()->producer.send(new ProducerRecord<>("identity.guest-identity.v1","denied",new byte[]{1})).get(10,TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(TopicAuthorizationException.class);
        }
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("identity.guest-identity.v1"));
            readAll(consumer,expected,aborted.eventId().toString());
            // Seek/replay intentionally repeats every event; this test has no state-changing projection.
            consumer.seekToBeginning(consumer.assignment());
            readAll(consumer,expected,aborted.eventId().toString());
        }
        System.out.println("PASS CDC committed byte-for-byte Protobuf, schema/trace headers, rollback absent, stable-ID replay, readonly client Write denied; committed="+committed.eventId()+" rolled_back="+aborted.eventId());
    }
    private void readAll(KafkaConsumer<String,byte[]> consumer,Map<String,byte[]> expected,String aborted) throws Exception {
        Set<String> seen=new HashSet<>();
        long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos(),quietUntil=Long.MAX_VALUE;
        while(System.nanoTime()<deadline && System.nanoTime()<quietUntil) {
            for(var record:consumer.poll(Duration.ofMillis(500))) {
                assertThat(record.value()).isNotNull();
                var envelope=EventEnvelopeV1.parseFrom(record.value());
                assertThat(envelope.getEventId()).isNotEqualTo(aborted);
                // Other browser fixtures may create additional valid guest events after the snapshot.
                if(expected.containsKey(envelope.getEventId()))assertThat(record.value()).isEqualTo(expected.get(envelope.getEventId()));
                assertThat(record.key()).isEqualTo(envelope.getAggregateId());
                assertThat(new String(record.headers().lastHeader("id").value(),StandardCharsets.UTF_8)).isEqualTo(envelope.getEventId());
                assertThat(new String(record.headers().lastHeader("trace_id").value(),StandardCharsets.UTF_8)).isEqualTo(envelope.getTraceId());
                assertThat(new String(record.headers().lastHeader("schema_group").value(),StandardCharsets.UTF_8)).isEqualTo("identity");
                assertThat(new String(record.headers().lastHeader("schema_artifact").value(),StandardCharsets.UTF_8)).isEqualTo("guest-identity-events");
                assertThat(new String(record.headers().lastHeader("schema_version").value(),StandardCharsets.UTF_8)).isEqualTo("1");
                seen.add(envelope.getEventId());
            }
            if(seen.containsAll(expected.keySet()) && quietUntil==Long.MAX_VALUE)quietUntil=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        }
        assertThat(seen).containsAll(expected.keySet());
    }
}
