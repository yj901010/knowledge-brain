package io.knowledgebrain.knowledge;

import io.knowledgebrain.contracts.knowledge.citation.v2.*;
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
class CitationKafkaIntegrationTest {
    @Test void allNineteenPinnedPayloadFieldsAndEnvelopeHeadersSurviveDebeziumByteForByte() throws Exception {
        var data=new PGSimpleDataSource();data.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));data.setUser("brain_knowledge");
        data.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));Flyway.configure().dataSource(data).locations("classpath:db/migration").load().migrate();
        var citations=new CitationStore(data,Clock.systemUTC());var concepts=new ConceptStore(data,Clock.systemUTC());
        var claims=new ClaimStore(data,Clock.systemUTC());var sources=new SourceStore(data,Clock.systemUTC());
        var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);
        UUID subject=concepts.create(space,actor,"Citation subject","",trace).conceptId();
        UUID predicate=concepts.create(space,actor,"Citation predicate","",trace).conceptId();
        UUID object=concepts.create(space,actor,"Citation object","",trace).conceptId();
        var claim=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("CONCEPT",object),trace);
        var source=sources.create(space,actor,"Pinned original","http://127.0.0.1:1/unreachable#page","  Original\nmanual citation  ","No fetched or verified license",trace);
        var entry=citations.create(space,subject,claim.claimId(),actor,new CitationStore.Reference(source.sourceId(),1,1),trace);
        sources.change(space,source.sourceId(),actor,1,"Printed replacement",null,"Author, Book, 2026","Manual new license note",false,trace);
        claims.change(space,subject,claim.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("CONCEPT",subject),false,trace);
        // Separate parent edits emit their own events; only explicit re-pinning changes the citation.
        assertThat(citations.get(space,subject,claim.claimId(),entry.citationId())).isEqualTo(entry);
        citations.change(space,subject,claim.claimId(),entry.citationId(),actor,1,new CitationStore.Reference(source.sourceId(),2,2),false,trace);
        sources.change(space,source.sourceId(),actor,2,null,null,null,null,true,trace);
        citations.change(space,subject,claim.claimId(),entry.citationId(),actor,2,null,true,trace);
        var history=citations.history(space,subject,claim.claimId(),entry.citationId(),Long.MAX_VALUE,10);
        var rows=new JdbcTemplate(data).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='citation-v2'",
            (rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),entry.citationId());
        var expected=new HashMap<String,byte[]>();rows.forEach(row->expected.put(row.getKey(),row.getValue()));assertThat(expected).hasSize(3);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("knowledge.citation.v2"));var found=new HashSet<String>();var versions=new ArrayList<Long>();var types=new ArrayList<String>();
            long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<3 && System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!entry.citationId().toString().equals(record.key()))continue;
                var event=EventEnvelopeV2.parseFrom(record.value());var payload=ClaimCitationChangedPayloadV2.parseFrom(event.getPayload());
                assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getAggregateType()).isEqualTo("ClaimCitation");
                assertThat(event.getAggregateId()).isEqualTo(entry.citationId().toString());assertThat(event.getSpaceId()).isEqualTo(space.toString());
                assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);assertThat(event.getOccurredAtEpochMillis()).isPositive();
                assertThat(event.getEventVersion()).isEqualTo(2);assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(UUID.fromString(event.getEventId()).version()).isEqualTo(7);
                assertThat(payload.getVersion()).isBetween(1L,3L);var revision=history.get(3-(int)payload.getVersion());
                assertThat(payload.getCitationId()).isEqualTo(entry.citationId().toString());assertThat(payload.getClaimId()).isEqualTo(claim.claimId().toString());assertThat(payload.getSubjectConceptId()).isEqualTo(subject.toString());
                assertThat(payload.getClaimRevisionId()).isEqualTo(revision.claim().revisionId().toString());assertThat(payload.getClaimVersion()).isEqualTo(revision.claim().version());
                assertThat(payload.getPredicateConceptId()).isEqualTo(predicate.toString());assertThat(payload.getObjectType()).isEqualTo("CONCEPT");
                assertThat(payload.getObjectConceptId()).isEqualTo(payload.getVersion()==1?object.toString():subject.toString());
                assertThat(payload.getSourceId()).isEqualTo(source.sourceId().toString());assertThat(payload.getSourceRevisionId()).isEqualTo(revision.source().revisionId().toString());
                assertThat(payload.getSourceVersion()).isEqualTo(payload.getVersion()==1?1L:2L);assertThat(payload.getTitle()).isEqualTo(payload.getVersion()==1?"Pinned original":"Printed replacement");
                assertThat(payload.getUrl()).isEqualTo(payload.getVersion()==1?"http://127.0.0.1:1/unreachable#page":"");
                assertThat(payload.getCitation()).isEqualTo(payload.getVersion()==1?"  Original\nmanual citation  ":"Author, Book, 2026");
                assertThat(payload.getLicenseNote()).isEqualTo(payload.getVersion()==1?"No fetched or verified license":"Manual new license note");
                assertThat(payload.getRevisionId()).isEqualTo(revision.revisionId().toString());assertThat(payload.getState()).isEqualTo(payload.getVersion()==3?"RETIRED":"ACTIVE");assertThat(payload.getVisibility()).isEqualTo("PRIVATE");
                assertThat(header(record,"trace_id")).isEqualTo(trace);assertThat(header(record,"schema_group")).isEqualTo("knowledge");
                assertThat(header(record,"schema_artifact")).isEqualTo("citation-events-v2");assertThat(header(record,"schema_version")).isEqualTo("1");
                if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
            assertThat(types).containsExactly("ClaimCitationCreated","ClaimCitationUpdated","ClaimCitationRetired");
        }
    }
    private static String header(ConsumerRecord<String,byte[]> record,String name) {
        var header=record.headers().lastHeader(name);assertThat(header).as(name).isNotNull();return new String(header.value(),StandardCharsets.UTF_8);
    }
}
