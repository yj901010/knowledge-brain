package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.mapping.v1.*;
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
class ExternalMappingKafkaIntegrationTest {
    @Test void allTwentyTwoPinnedPayloadFieldsAndEnvelopeHeadersSurviveDebeziumByteForByte() throws Exception {
        var data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");
        data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        var mappings=new ExternalMappingStore(data,Clock.systemUTC());var concepts=new ConceptStore(data,Clock.systemUTC());var sources=new SourceStore(data,Clock.systemUTC());
        var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);
        UUID concept=concepts.create(space,actor,"Original Concept","Original meaning",trace).conceptId();
        var source=sources.create(space,actor,"Original Source","https://example.invalid/no-fetch","Original citation","Original license",trace);
        var entry=mappings.create(space,concept,actor,ExternalMappingStore.edit("System","Code","2026","Label",source.sourceId(),1,1),trace);
        concepts.change(space,concept,actor,1,"Edited Concept","Edited meaning",false,trace);
        sources.change(space,source.sourceId(),actor,1,"Edited Source",null,"Edited citation","Edited license",false,trace);
        assertThat(mappings.get(space,concept,entry.mappingId())).isEqualTo(entry);
        mappings.change(space,concept,entry.mappingId(),actor,1,ExternalMappingStore.edit("System","Code","2027","New label",source.sourceId(),2,2),false,trace);
        mappings.change(space,concept,entry.mappingId(),actor,2,null,true,trace);
        var history=mappings.history(space,concept,entry.mappingId(),Long.MAX_VALUE,10);
        var rows=new JdbcTemplate(data).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='external-mapping'",
            (rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),entry.mappingId());
        var expected=new HashMap<String,byte[]>();rows.forEach(row->expected.put(row.getKey(),row.getValue()));assertThat(expected).hasSize(3);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("knowledge.external-mapping.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();var types=new ArrayList<String>();
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<3 && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!entry.mappingId().toString().equals(record.key()))continue;
                var event=EventEnvelopeV1.parseFrom(record.value());var payload=ExternalMappingChangedPayloadV1.parseFrom(event.getPayload());
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getAggregateType()).isEqualTo("ExternalMapping");
                assertThat(event.getAggregateId()).isEqualTo(entry.mappingId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
                assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
                assertThat(event.getEventVersion()).isEqualTo(1);assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);
                assertThat(payload.getVersion()).isBetween(1L,3L);var revision=history.get(3-(int)payload.getVersion());
                assertThat(payload.getMappingId()).isEqualTo(entry.mappingId().toString());assertThat(payload.getConceptId()).isEqualTo(concept.toString());
                assertThat(payload.getConceptRevisionId()).isEqualTo(revision.concept().revisionId().toString());assertThat(payload.getConceptVersion()).isEqualTo(revision.concept().version());
                assertThat(payload.getConceptTitle()).isEqualTo(revision.concept().title());assertThat(payload.getConceptDescription()).isEqualTo(revision.concept().description());
                assertThat(payload.getSystem()).isEqualTo(revision.system());assertThat(payload.getCode()).isEqualTo(revision.code());assertThat(payload.getSystemVersion()).isEqualTo(revision.systemVersion());assertThat(payload.getLabel()).isEqualTo(revision.label());
                assertThat(payload.getSourceId()).isEqualTo(revision.source().sourceId().toString());assertThat(payload.getSourceRevisionId()).isEqualTo(revision.source().revisionId().toString());assertThat(payload.getSourceVersion()).isEqualTo(revision.source().version());
                assertThat(payload.getSourceTitle()).isEqualTo(revision.source().title());assertThat(payload.getSourceUrl()).isEqualTo(revision.source().url()==null?"":revision.source().url());assertThat(payload.getSourceCitation()).isEqualTo(revision.source().citation());assertThat(payload.getSourceLicenseNote()).isEqualTo(revision.source().licenseNote());
                assertThat(payload.getMappingState()).isEqualTo("CANDIDATE");
                assertThat(payload.getRevisionId()).isEqualTo(revision.revisionId().toString());assertThat(payload.getState()).isEqualTo(payload.getVersion()==3?"RETIRED":"ACTIVE");assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
                assertThat(header(record,"trace_id")).isEqualTo(trace);assertThat(header(record,"schema_group")).isEqualTo("knowledge");
                assertThat(header(record,"schema_artifact")).isEqualTo("external-mapping-events");assertThat(header(record,"schema_version")).isEqualTo("1");
                if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
            assertThat(types).containsExactly("ExternalMappingCreated","ExternalMappingUpdated","ExternalMappingRetired");
        }
    }
    private static String header(ConsumerRecord<String,byte[]> record,String name) {
        var header=record.headers().lastHeader(name);assertThat(header).as(name).isNotNull();return new String(header.value(),StandardCharsets.UTF_8);
    }
}
