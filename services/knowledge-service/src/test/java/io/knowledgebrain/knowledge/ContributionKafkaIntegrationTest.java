package io.knowledgebrain.knowledge;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.knowledgebrain.contracts.knowledge.contribution.v1.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP",matches=".+")
class ContributionKafkaIntegrationTest {
    @Test void reviewMergeAndCanonicalRevisionTravelThroughOwnOutboxWithExactBytes()throws Exception{
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();var ids=new UuidV7(Clock.systemUTC());var concepts=new ConceptStore(source,Clock.systemUTC());var store=new ContributionStore(source,concepts,Clock.systemUTC());UUID space=ids.next(),author=ids.next(),reviewer=ids.next();String trace="b".repeat(32);var p=store.submit(space,author,null,0,"CDC public fixture","Explicit public text","Contract validation",trace);store.decide(space,p.contributionId(),reviewer,1,"APPROVED","Reviewed",trace);store.merge(space,p.contributionId(),reviewer,2,trace);
        record Expected(byte[] bytes,String artifact,UUID aggregate){}
        var expected=new HashMap<String,Expected>();new JdbcTemplate(source).query("SELECT id,payload,schema_artifact,aggregateid FROM outbox_event WHERE (aggregateid=? AND aggregatetype='contribution') OR (aggregateid=? AND aggregatetype='concept')",(rs,n)->{expected.put(rs.getObject(1,UUID.class).toString(),new Expected(rs.getBytes(2),rs.getString(3),rs.getObject(4,UUID.class)));return 0;},p.contributionId(),p.conceptId());assertThat(expected).hasSize(4);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)){
            consumer.subscribe(List.of("knowledge.concept.v1","knowledge.contribution.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<expected.size()&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))){if(!Set.of(p.contributionId().toString(),p.conceptId().toString()).contains(record.key()))continue;var event=EventEnvelopeV1.parseFrom(record.value());var value=expected.get(event.getEventId());assertThat(value).isNotNull();assertThat(record.value()).isEqualTo(value.bytes());assertThat(record.key()).isEqualTo(value.aggregate().toString());assertThat(record.topic()).isEqualTo("knowledge."+("concept-events".equals(value.artifact())?"concept":"contribution")+".v1");assertThat(event.getSpaceId()).isEqualTo(space.toString());assertThat(event.getProducer()).isEqualTo("knowledge-service");assertThat(event.getTraceId()).isEqualTo(trace);assertThat(new String(record.headers().lastHeader("trace_id").value())).isEqualTo(trace);assertThat(new String(record.headers().lastHeader("schema_artifact").value())).isEqualTo(value.artifact());assertThat(new String(record.headers().lastHeader("schema_group").value())).isEqualTo("knowledge");if(found.add(event.getEventId())&&"contribution-events".equals(value.artifact())){var payload=ContributionChangedPayloadV1.parseFrom(event.getPayload());assertThat(payload.getAuthorId()).isEqualTo(author.toString());versions.add(payload.getVersion());}else if("concept-events".equals(value.artifact()))assertThat(io.knowledgebrain.contracts.knowledge.v1.ConceptChangedPayloadV1.parseFrom(event.getPayload()).getVisibility()).isEqualTo("PUBLIC");}
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L,3L);
        }
    }
}
