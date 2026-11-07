package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.qualifier.v2.*;
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
class QualifierKafkaIntegrationTest {
    @Test void allFourteenPinnedPayloadFieldsAndEnvelopeHeadersSurviveDebeziumByteForByte() throws Exception {
        var data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");
        data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        var qualifiers=new QualifierStore(data,Clock.systemUTC());var concepts=new ConceptStore(data,Clock.systemUTC());
        var claims=new ClaimStore(data,Clock.systemUTC());
        var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);
        UUID subject=concepts.create(space,actor,"Qualifier subject","",trace).conceptId();
        UUID predicate=concepts.create(space,actor,"Qualifier predicate","",trace).conceptId();
        UUID object=concepts.create(space,actor,"Qualifier object","",trace).conceptId();
        var claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",object),trace);
        var entry=qualifiers.create(space,subject,claim.claimId(),actor,QualifierStore.edit(predicate,QualifierStore.value("NUMBER","9007199254740993.123456789012345678"),1),trace);
        claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",subject),false,trace);
        assertThat(qualifiers.get(space,subject,claim.claimId(),entry.qualifierId())).isEqualTo(entry);
        qualifiers.change(space,subject,claim.claimId(),entry.qualifierId(),actor,1,QualifierStore.edit(predicate,QualifierStore.value("BOOLEAN","false"),2),false,trace);
        qualifiers.change(space,subject,claim.claimId(),entry.qualifierId(),actor,2,null,true,trace);
        var history=qualifiers.history(space,subject,claim.claimId(),entry.qualifierId(),Long.MAX_VALUE,10);
        var rows=new JdbcTemplate(data).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='qualifier-v2'",
            (rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),entry.qualifierId());
        var expected=new HashMap<String,byte[]>();rows.forEach(row->expected.put(row.getKey(),row.getValue()));assertThat(expected).hasSize(3);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("knowledge.qualifier.v2"));var found=new HashSet<String>();var versions=new ArrayList<Long>();var types=new ArrayList<String>();
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<3 && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!entry.qualifierId().toString().equals(record.key()))continue;
                var event=EventEnvelopeV2.parseFrom(record.value());var payload=ClaimQualifierChangedPayloadV2.parseFrom(event.getPayload());
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getAggregateType()).isEqualTo("ClaimQualifier");
                assertThat(event.getAggregateId()).isEqualTo(entry.qualifierId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
                assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
                assertThat(event.getEventVersion()).isEqualTo(2);assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);
                assertThat(payload.getVersion()).isBetween(1L,3L);var revision=history.get(3-(int)payload.getVersion());
                assertThat(payload.getQualifierId()).isEqualTo(entry.qualifierId().toString());assertThat(payload.getClaimId()).isEqualTo(claim.claimId().toString());assertThat(payload.getSubjectConceptId()).isEqualTo(subject.toString());
                assertThat(payload.getClaimRevisionId()).isEqualTo(revision.claim().revisionId().toString());assertThat(payload.getClaimVersion()).isEqualTo(revision.claim().version());
                assertThat(payload.getClaimPredicateConceptId()).isEqualTo(predicate.toString());
                assertThat(payload.getClaimObjectConceptId()).isEqualTo(payload.getVersion()==1?object.toString():subject.toString());
                assertThat(payload.getPredicateConceptId()).isEqualTo(predicate.toString());
                assertThat(payload.getValueType()).isEqualTo(revision.value().type());assertThat(payload.getValueText()).isEqualTo(revision.value().value());
                assertThat(payload.getRevisionId()).isEqualTo(revision.revisionId().toString());assertThat(payload.getState()).isEqualTo(payload.getVersion()==3?"RETIRED":"ACTIVE");assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
                assertThat(header(record,"trace_id")).isEqualTo(trace);assertThat(header(record,"schema_group")).isEqualTo("knowledge");
                assertThat(header(record,"schema_artifact")).isEqualTo("qualifier-events-v2");assertThat(header(record,"schema_version")).isEqualTo("1");
                if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
            assertThat(types).containsExactly("ClaimQualifierCreated","ClaimQualifierUpdated","ClaimQualifierRetired");
        }
    }
    private static String header(ConsumerRecord<String,byte[]> record,String name) {
        var header=record.headers().lastHeader(name);assertThat(header).as(name).isNotNull();return new String(header.value(),StandardCharsets.UTF_8);
    }
}
