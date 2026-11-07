package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.alias.v1.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP",matches=".+")
class AliasKafkaIntegrationTest {
    @Test void privateAliasHistoryTravelsThroughDebeziumWithExactWireBytesAndIndependentTopicKey() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var concepts=new ConceptStore(source,Clock.systemUTC());var aliases=new AliasStore(source,Clock.systemUTC());
        var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);
        var concept=concepts.create(space,actor,"Independent parent","",trace);
        var alias=aliases.create(space,concept.conceptId(),actor,"Kafka alias evidence",trace);
        aliases.change(space,concept.conceptId(),alias.aliasId(),actor,1,"Changed alias evidence",false,trace);
        aliases.change(space,concept.conceptId(),alias.aliasId(),actor,2,null,true,trace);
        assertThat(concepts.get(space,concept.conceptId()).version()).isEqualTo(1);
        var rows=new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='alias'",
            (rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),alias.aliasId());
        var expected=new HashMap<String,byte[]>();rows.forEach(row->expected.put(row.getKey(),row.getValue()));
        assertThat(expected).hasSize(3);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("knowledge.alias.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();
            var types=new ArrayList<String>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<3 && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!alias.aliasId().toString().equals(record.key()))continue;
                var event=EventEnvelopeV1.parseFrom(record.value());var payload=AliasChangedPayloadV1.parseFrom(event.getPayload());
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getAggregateType()).isEqualTo("Alias");
                assertThat(event.getAggregateId()).isEqualTo(alias.aliasId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
                assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);
                assertThat(event.getEventVersion()).isEqualTo(1);assertThat(event.getProducer()).isEqualTo("knowledge-service");
                assertThat(payload.getAliasId()).isEqualTo(alias.aliasId().toString());assertThat(payload.getConceptId()).isEqualTo(concept.conceptId().toString());
                assertThat(payload.getVisibility()).isEqualTo("PRIVATE");assertThat(payload.getScope()).isEqualTo("SPACE");
                assertThat(UUID.fromString(payload.getRevisionId()).version()).isEqualTo(7);
                assertThat(header(record,"trace_id")).isEqualTo(trace);assertThat(header(record,"schema_group")).isEqualTo("knowledge");
                assertThat(header(record,"schema_artifact")).isEqualTo("alias-events");assertThat(header(record,"schema_version")).isEqualTo("1");
                if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
            assertThat(types).containsExactly("AliasCreated","AliasUpdated","AliasRetired");
        }
    }
    private static String header(ConsumerRecord<String,byte[]> record,String name) {
        var header=record.headers().lastHeader(name);assertThat(header).as(name).isNotNull();return new String(header.value(),StandardCharsets.UTF_8);
    }
}
