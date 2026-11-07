package io.knowledgebrain.collaboration;
import io.knowledgebrain.contracts.collaboration.group.v1.EventEnvelopeV1;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_GROUP_KAFKA_BOOTSTRAP",matches=".+")
class GroupKafkaIntegrationTest {
 @Test void realCdcBytesHeadersAndUncommittedReplay()throws Exception{
  var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_COLLABORATION_DATABASE_URL"));source.setUser("brain_collaboration");source.setPassword(System.getenv("BRAIN_TEST_COLLABORATION_DATABASE_PASSWORD"));var ids=new UuidV7(Clock.systemUTC());var owner=ids.next();var trace="a".repeat(32);var store=new GroupStore(source,Clock.systemUTC());var g=store.request(owner,UUID.randomUUID().toString().replace("-",""),"Kafka group","literal",trace);store.activate(g.groupId(),owner,ids.next(),trace);UUID aggregate=g.groupId();
  var jdbc=new JdbcTemplate(source);var expected=new HashMap<String,byte[]>();for(var bytes:jdbc.queryForList("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,aggregate)){var e=EventEnvelopeV1.parseFrom(bytes);expected.put(e.getEventId(),bytes);}assertThat(expected).hasSize(2);
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_GROUP_KAFKA_BOOTSTRAP"));options.put("group.id","group-validation-"+UUID.randomUUID());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.keystore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/group-kafka-test/ca.crt")));options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/group-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/group-kafka-test/user.key")));
  for(int replay=0;replay<2;replay++){var seen=new HashSet<String>();try(var consumer=new KafkaConsumer<String,byte[]>(options)){consumer.subscribe(List.of("collaboration.group.v1"));var deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(!seen.containsAll(expected.keySet())&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(300))){var e=EventEnvelopeV1.parseFrom(record.value());if(!expected.containsKey(e.getEventId()))continue;assertThat(record.value()).isEqualTo(expected.get(e.getEventId()));assertThat(record.key()).isEqualTo(aggregate.toString());assertThat(e.getProducer()).isEqualTo("collaboration-service");assertThat(e.getAggregateType()).isEqualTo("Group");assertThat(e.getTraceId()).isEqualTo(trace);var headers=new HashMap<String,String>();record.headers().forEach(h->headers.put(h.key(),new String(h.value(),StandardCharsets.UTF_8)));assertThat(headers).containsEntry("id",e.getEventId()).containsEntry("trace_id",trace).containsEntry("schema_group","collaboration").containsEntry("schema_artifact","group-events").containsEntry("schema_version","1");seen.add(e.getEventId());}}assertThat(seen).containsAll(expected.keySet());}
  System.out.println("PASS collaboration.group.v1 byte-exact Protobuf CDC, immutable event IDs, trace/schema headers and unacknowledged replay");
 }
}
