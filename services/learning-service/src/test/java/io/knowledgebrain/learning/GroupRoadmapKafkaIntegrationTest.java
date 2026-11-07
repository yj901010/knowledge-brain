package io.knowledgebrain.learning;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;
import org.apache.kafka.clients.consumer.*;import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.learning.grouproadmap.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP",matches=".+")
class GroupRoadmapKafkaIntegrationTest {
 @Test void actualCdcCarriesExactPinnedGraphAndReplayIsIdempotent()throws Exception{
  var f=new GroupRoadmapStoreIntegrationTest();f.setup();var source=f.f.publish();
  var link=f.store.attach(f.f.space,f.f.sourceSpace,source.roadmapId(),1,f.f.actor,f.f.trace).link();
  f.store.retire(f.f.space,link.linkId(),1,f.f.actor,f.f.trace);
  var expected=f.f.sql.query("SELECT payload FROM outbox_event WHERE aggregateid=? ORDER BY occurred_at,id",(rs,n)->rs.getBytes(1),link.linkId());assertEquals(2,expected.size());
  var ids=new UuidV7(Clock.systemUTC());
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP"));options.put("group.id","learning-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/learning-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/learning-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/learning-kafka-test/user.key")));
  try(var consumer=new KafkaConsumer<String,byte[]>(options)){
   consumer.subscribe(List.of("learning.group-roadmap.v1"));var seen=new HashSet<String>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();ConsumerRecord<String,byte[]> first=null;
   while(seen.size()<2&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))){
    if(!link.linkId().toString().equals(record.key()))continue;
    assertTrue(expected.stream().anyMatch(v->Arrays.equals(v,record.value())));var e=GroupRoadmapEnvelopeV1.parseFrom(record.value());var p=GroupRoadmapChangedV1.parseFrom(e.getPayload());
    assertEquals(f.f.space.toString(),e.getSpaceId());assertEquals(f.f.actor.toString(),e.getActorId());assertEquals(f.f.trace,e.getTraceId());assertEquals(2,p.getNodesCount());assertEquals(1,p.getEdgesCount());assertEquals("PRIVATE",p.getVisibility());
    for(var pair:Map.of("trace_id",f.f.trace,"schema_group","learning","schema_artifact","group-roadmap-events","schema_version","1").entrySet())assertEquals(pair.getValue(),new String(record.headers().lastHeader(pair.getKey()).value(),StandardCharsets.UTF_8));
    seen.add(e.getEventId());if(first==null)first=record;
   }assertEquals(2,seen.size());assertNotNull(first);
   consumer.seek(new org.apache.kafka.common.TopicPartition(first.topic(),first.partition()),first.offset());boolean replay=false;deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
   while(!replay&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1)))if(link.linkId().toString().equals(record.key())){assertFalse(seen.add(GroupRoadmapEnvelopeV1.parseFrom(record.value()).getEventId()));replay=true;}
   assertTrue(replay);assertEquals(2,seen.size());
  }
 }
}
