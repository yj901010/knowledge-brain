package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.claim.v2.*;
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
class ClaimKafkaIntegrationTest {
    @Test void privateClaimRevisionStreamTravelsThroughDebeziumWithExactWireBytesAndClaimKey() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));
        source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var concepts=new ConceptStore(source,Clock.systemUTC());var claims=new ClaimStore(source,Clock.systemUTC());
        var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);
        UUID subject=concepts.create(space,actor,"Kafka subject","",trace).conceptId();
        UUID predicate=concepts.create(space,actor,"Kafka predicate","",trace).conceptId();
        UUID object=concepts.create(space,actor,"Kafka object","",trace).conceptId();
        UUID replacement=concepts.create(space,actor,"Kafka replacement","",trace).conceptId();
        var claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",object),trace);
        claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",replacement),false,trace);
        claims.change(space,subject,claim.claimId(),actor,2,null,null,true,trace);
        for(UUID id:List.of(subject,predicate,object,replacement))assertThat(concepts.get(space,id).version()).isEqualTo(1);
        var rows=new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='claim-v2'",
            (rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),claim.claimId());
        var expected=new HashMap<String,byte[]>();rows.forEach(row->expected.put(row.getKey(),row.getValue()));assertThat(expected).hasSize(3);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("knowledge.claim.v2"));var found=new HashSet<String>();var versions=new ArrayList<Long>();var types=new ArrayList<String>();
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<3 && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!claim.claimId().toString().equals(record.key()))continue;
                var event=EventEnvelopeV2.parseFrom(record.value());var payload=ClaimChangedPayloadV2.parseFrom(event.getPayload());
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getAggregateType()).isEqualTo("Claim");
                assertThat(event.getAggregateId()).isEqualTo(claim.claimId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
                assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);
                assertThat(event.getEventVersion()).isEqualTo(2);assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);
                assertThat(payload.getClaimId()).isEqualTo(claim.claimId().toString());assertThat(payload.getSubjectConceptId()).isEqualTo(subject.toString());
                assertThat(payload.getPredicateConceptId()).isEqualTo(predicate.toString());assertThat(payload.getObjectType()).isEqualTo("CONCEPT");
                assertThat(payload.getObjectConceptId()).isEqualTo((payload.getVersion()==1?object:replacement).toString());
                assertThat(payload.getVisibility()).isEqualTo("PRIVATE");assertThat(payload.getVerificationState()).isEqualTo("DRAFT");
                assertThat(payload.getState()).isEqualTo(payload.getVersion()==3?"RETIRED":"ACTIVE");assertThat(UUID.fromString(payload.getRevisionId()).version()).isEqualTo(7);
                assertThat(header(record,"trace_id")).isEqualTo(trace);assertThat(header(record,"schema_group")).isEqualTo("knowledge");
                assertThat(header(record,"schema_artifact")).isEqualTo("claim-events-v2");assertThat(header(record,"schema_version")).isEqualTo("1");
                if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
            assertThat(types).containsExactly("ClaimCreated","ClaimUpdated","ClaimRetired");
        }
    }
    private static String header(ConsumerRecord<String,byte[]> record,String name) {
        var header=record.headers().lastHeader(name);assertThat(header).as(name).isNotNull();return new String(header.value(),StandardCharsets.UTF_8);
    }
}
