package io.knowledgebrain.space;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.knowledgebrain.contracts.space.publicknowledge.v1.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_PUBLIC_REVIEWER_ID",matches=".+")
class PublicReviewerKafkaIntegrationTest {
    @Test void actualOperatorRoleAssignmentAndRevocationMatchContractAndCommittedBytes()throws Exception{
        UUID principal=UUID.fromString(System.getenv("BRAIN_TEST_PUBLIC_REVIEWER_ID"));assertThat(principal.version()).isEqualTo(7);var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_SPACE_DATABASE_URL"));source.setUser("brain_space");source.setPassword(System.getenv("BRAIN_TEST_SPACE_DATABASE_PASSWORD"));
        var expected=new HashMap<String,byte[]>();new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregatetype='public-knowledge' AND aggregateid=? ORDER BY occurred_at,id",(rs,n)->{expected.put(rs.getObject(1,UUID.class).toString(),rs.getBytes(2));return 0;},principal);assertThat(expected).hasSize(2);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_SPACE_KAFKA_BOOTSTRAP"));options.put("group.id","space-validation-"+UUID.randomUUID());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/space-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/space-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/space-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)){consumer.subscribe(List.of("space.public-knowledge.v1"));var found=new HashSet<String>();var states=new ArrayList<String>();long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(found.size()<2&&System.nanoTime()<end)for(var record:consumer.poll(Duration.ofSeconds(1))){if(!principal.toString().equals(record.key()))continue;var event=EventEnvelopeV1.parseFrom(record.value());assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getProducer()).isEqualTo("space-service");assertThat(event.getAggregateType()).isEqualTo("PublicReviewer");assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);assertThat(event.getTraceId()).matches("[0-9a-f]{32}");var payload=PublicReviewerChangedPayloadV1.parseFrom(event.getPayload());assertThat(payload.getPrincipalId()).isEqualTo(principal.toString());assertThat(payload.getSpaceId()).isEqualTo(event.getSpaceId());assertThat(payload.getRole()).isEqualTo("REVIEWER");assertThat(new String(record.headers().lastHeader("schema_group").value())).isEqualTo("space");assertThat(new String(record.headers().lastHeader("schema_artifact").value())).isEqualTo("public-knowledge-events");assertThat(new String(record.headers().lastHeader("trace_id").value())).isEqualTo(event.getTraceId());if(found.add(event.getEventId()))states.add(payload.getState());}assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(states).containsExactly("ACTIVE","RETIRED");}
    }
}
