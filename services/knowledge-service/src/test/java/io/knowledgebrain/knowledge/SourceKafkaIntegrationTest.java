package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.source.v1.*;
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
class SourceKafkaIntegrationTest {
    @Test void manualSourceMetadataTravelsThroughDebeziumWithoutChangingWireBytesOrFetchingTheUrl() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var sources=new SourceStore(source,Clock.systemUTC());var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);
        // A syntactically valid unreachable address exercises metadata registration without an external request.
        var entry=sources.create(space,actor,"Manual source","http://127.0.0.1:1/user-entered#section","  Supplied original\ncitation  ","User annotation, no license verification",trace);
        sources.change(space,entry.sourceId(),actor,1,"Printed replacement",null,"Author, Book, 2026","Manual license note",false,trace);
        sources.change(space,entry.sourceId(),actor,2,null,null,null,null,true,trace);
        var rows=new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='source'",
            (rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),entry.sourceId());
        var expected=new HashMap<String,byte[]>();rows.forEach(row->expected.put(row.getKey(),row.getValue()));assertThat(expected).hasSize(3);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("knowledge.source.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();var types=new ArrayList<String>();
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<3 && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!entry.sourceId().toString().equals(record.key()))continue;
                var event=EventEnvelopeV1.parseFrom(record.value());var payload=SourceChangedPayloadV1.parseFrom(event.getPayload());
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getAggregateType()).isEqualTo("Source");
                assertThat(event.getAggregateId()).isEqualTo(entry.sourceId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
                assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
                assertThat(event.getEventVersion()).isEqualTo(1);assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);
                assertThat(payload.getSourceId()).isEqualTo(entry.sourceId().toString());assertThat(payload.getTitle()).isEqualTo(payload.getVersion()==1?"Manual source":"Printed replacement");
                assertThat(payload.getUrl()).isEqualTo(payload.getVersion()==1?"http://127.0.0.1:1/user-entered#section":"");
                assertThat(payload.getCitation()).isEqualTo(payload.getVersion()==1?"  Supplied original\ncitation  ":"Author, Book, 2026");
                assertThat(payload.getLicenseNote()).isEqualTo(payload.getVersion()==1?"User annotation, no license verification":"Manual license note");
                assertThat(payload.getVisibility()).isEqualTo("PRIVATE");assertThat(payload.getState()).isEqualTo(payload.getVersion()==3?"RETIRED":"ACTIVE");
                assertThat(UUID.fromString(payload.getRevisionId()).version()).isEqualTo(7);
                assertThat(header(record,"trace_id")).isEqualTo(trace);assertThat(header(record,"schema_group")).isEqualTo("knowledge");
                assertThat(header(record,"schema_artifact")).isEqualTo("source-events");assertThat(header(record,"schema_version")).isEqualTo("1");
                if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
            assertThat(types).containsExactly("SourceCreated","SourceUpdated","SourceRetired");
        }
    }
    private static String header(ConsumerRecord<String,byte[]> record,String name) {
        var header=record.headers().lastHeader(name);assertThat(header).as(name).isNotNull();return new String(header.value(),StandardCharsets.UTF_8);
    }
}
