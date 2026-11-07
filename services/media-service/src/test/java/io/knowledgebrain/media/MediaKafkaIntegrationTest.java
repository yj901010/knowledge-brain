package io.knowledgebrain.media;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.knowledgebrain.contracts.media.v1.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_MEDIA_KAFKA_BOOTSTRAP",matches=".+")
class MediaKafkaIntegrationTest {
    @Test void metadataChangesThroughDebeziumWithStableBytesIdsAndOrdering() throws Exception {
        var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_MEDIA_DATABASE_URL"));source.setUser("brain_media");source.setPassword(System.getenv("BRAIN_TEST_MEDIA_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var store=new MediaStore(source,Clock.systemUTC());var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next();String trace="e".repeat(32);var note=store.create(space,actor,new MediaInput.Upload("a.pdf","application/pdf",10,"a".repeat(64)),trace);store.complete(space,note.mediaId(),actor,"immutable-version",trace);
        var bytes=new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='file'",(rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),note.mediaId());var expected=new HashMap<String,byte[]>();bytes.forEach(e->expected.put(e.getKey(),e.getValue()));
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_MEDIA_KAFKA_BOOTSTRAP"));options.put("group.id","media-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/media-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/media-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/media-kafka-test/user.key")));
        try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
            consumer.subscribe(List.of("media.file.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();long end=System.nanoTime()+Duration.ofSeconds(60).toNanos();
            while(found.size()<2 && System.nanoTime()<end)for(var record:consumer.poll(Duration.ofSeconds(1))) {
                if(!note.mediaId().toString().equals(record.key()))continue;var event=MediaEventEnvelopeV1.parseFrom(record.value());assertThat(record.value()).isEqualTo(expected.get(event.getEventId()));assertThat(event.getSpaceId()).isEqualTo(space.toString());assertThat(event.getActorId()).isEqualTo(actor.toString());assertThat(event.getTraceId()).isEqualTo(trace);
                assertThat(new String(record.headers().lastHeader("trace_id").value())).isEqualTo(trace);assertThat(new String(record.headers().lastHeader("schema_artifact").value())).isEqualTo("file-events");assertThat(new String(record.headers().lastHeader("schema_group").value())).isEqualTo("media");
                if(found.add(event.getEventId()))versions.add(MediaChangedPayloadV1.parseFrom(event.getPayload()).getVersion());
            }
            assertThat(found).containsExactlyInAnyOrderElementsOf(expected.keySet());assertThat(versions).containsExactly(1L,2L);
        }
    }
}
