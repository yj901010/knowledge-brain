package io.knowledgebrain.space;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.knowledgebrain.contracts.space.publiccapability.v1.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_PUBLIC_CAPABILITY_PRINCIPAL",matches=".+")
class PublicCapabilityKafkaIntegrationTest {
    @Test void actualOperatorGrantsAndRevocationsMatchCommittedSpaceBytes()throws Exception{
        UUID principal=UUID.fromString(System.getenv("BRAIN_TEST_PUBLIC_CAPABILITY_PRINCIPAL"));var ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_SPACE_DATABASE_URL"));ds.setUser("brain_space");ds.setPassword(System.getenv("BRAIN_TEST_SPACE_DATABASE_PASSWORD"));
        record Expected(byte[] bytes,String trace){}var expected=new HashMap<String,Expected>();new JdbcTemplate(ds).query("SELECT id,payload,trace_id FROM outbox_event WHERE aggregatetype='public-capability' AND aggregateid=?",(r,n)->{expected.put(r.getObject(1,UUID.class).toString(),new Expected(r.getBytes(2),r.getString(3)));return 0;},principal);assertThat(expected.size()).isGreaterThanOrEqualTo(4);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_SPACE_KAFKA_BOOTSTRAP"));options.put("group.id","space-validation-"+new UuidV7(Clock.systemUTC()).next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/space-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/space-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/space-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)){consumer.subscribe(List.of("space.public-capability.v1"));var found=new HashSet<String>();var states=new HashSet<String>();long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();while(found.size()<expected.size()&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))){if(!principal.toString().equals(record.key()))continue;var event=EventEnvelopeV1.parseFrom(record.value());assertThat(expected).containsKey(event.getEventId());var value=expected.get(event.getEventId());assertThat(record.value()).isEqualTo(value.bytes());assertThat(event.getAggregateId()).isEqualTo(principal.toString());assertThat(event.getTraceId()).isEqualTo(value.trace());assertThat(event.getProducer()).isEqualTo("space-service");assertThat(new String(record.headers().lastHeader("schema_group").value())).isEqualTo("space");assertThat(new String(record.headers().lastHeader("schema_artifact").value())).isEqualTo("public-capability-events");assertThat(new String(record.headers().lastHeader("trace_id").value())).isEqualTo(value.trace());var payload=PublicCapabilityChangedPayloadV1.parseFrom(event.getPayload());assertThat(payload.getPrincipalId()).isEqualTo(principal.toString());assertThat(payload.getProvenance()).contains("validation");states.add(payload.getCapability()+":"+payload.getState());found.add(event.getEventId());}
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(states).contains("CLAIM_EXPERT_VERIFY:ACTIVE","CLAIM_EXPERT_VERIFY:RETIRED","MAPPING_OFFICIAL_REVIEW:ACTIVE","MAPPING_OFFICIAL_REVIEW:RETIRED");}
    }
}
