package io.knowledgebrain.collaboration;
import io.knowledgebrain.contracts.collaboration.study.v1.*;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;
import org.apache.kafka.clients.consumer.*;import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_GROUP_KAFKA_BOOTSTRAP",matches=".+")
class StudyKafkaIntegrationTest {
 @Test void fullStudyLifecycleExactBytesTraceAndUnacknowledgedReplay()throws Exception{
  var f=new StudyStoreIntegrationTest();f.setup();var v=f.create(f.f.key()).study();var trace=f.f.trace;
  f.store.metadata(f.group,f.space,v.studyId(),f.f.owner,1,"Edited","Purpose",trace);f.store.state(f.group,f.space,v.studyId(),f.f.owner,2,"ACTIVE",trace);f.store.state(f.group,f.space,v.studyId(),f.f.owner,3,"COMPLETED",trace);f.store.state(f.group,f.space,v.studyId(),f.f.owner,4,"RETIRED",trace);
  var expected=new HashMap<String,byte[]>();for(var bytes:f.f.jdbc.queryForList("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,v.studyId())){var e=StudyEnvelopeV1.parseFrom(bytes);expected.put(e.getEventId(),bytes);}assertEquals(5,expected.size());
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_GROUP_KAFKA_BOOTSTRAP"));options.put("group.id","group-validation-"+UUID.randomUUID());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.keystore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/group-kafka-test/ca.crt")));options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/group-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/group-kafka-test/user.key")));
  for(int replay=0;replay<2;replay++){var seen=new HashSet<String>();try(var consumer=new KafkaConsumer<String,byte[]>(options)){consumer.subscribe(List.of("collaboration.study.v1"));long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();while(seen.size()<5&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(300))){var e=StudyEnvelopeV1.parseFrom(record.value());if(!expected.containsKey(e.getEventId()))continue;assertArrayEquals(expected.get(e.getEventId()),record.value());assertEquals(v.studyId().toString(),record.key());assertEquals(f.space.toString(),e.getSpaceId());assertEquals(trace,e.getTraceId());var p=StudySnapshotV1.parseFrom(e.getPayload());assertEquals(f.group.toString(),p.getGroupId());assertEquals("PRIVATE",p.getVisibility());
   for(var entry:Map.of("trace_id",trace,"schema_group","collaboration","schema_artifact","study-events","schema_version","1").entrySet())assertEquals(entry.getValue(),new String(record.headers().lastHeader(entry.getKey()).value(),StandardCharsets.UTF_8));seen.add(e.getEventId());}}
   assertEquals(expected.keySet(),seen);
  }System.out.println("PASS collaboration.study.v1 five byte-exact lifecycle snapshots, trace/schema headers and unacknowledged replay");
 }
}
