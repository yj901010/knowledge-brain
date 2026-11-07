package io.knowledgebrain.learning;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.*;import java.util.*;
import org.apache.kafka.clients.consumer.*;import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.learning.roadmapadoption.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP",matches=".+")
class RoadmapAdoptionKafkaIntegrationTest {
 @Test void exactPrivateAdoptionOutboxReachesKafkaAfterRetry()throws Exception{
  var fixture=new RoadmapAdoptionStoreIntegrationTest();fixture.setup();var original=fixture.publish();
  var a=fixture.store.adopt(fixture.space,fixture.sourceSpace,original.roadmapId(),1,fixture.actor,fixture.trace).adoption();
  assertFalse(fixture.store.adopt(fixture.space,fixture.sourceSpace,original.roadmapId(),1,fixture.actor,fixture.trace).created());
  byte[] expected=fixture.sql.queryForObject("SELECT payload FROM outbox_event WHERE aggregateid=?",byte[].class,a.adoptionId());
  var ids=new UuidV7(Clock.systemUTC());
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP"));options.put("group.id","learning-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/learning-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/learning-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/learning-kafka-test/user.key")));


  try(var consumer=new KafkaConsumer<String,byte[]>(options)){
   consumer.subscribe(List.of("learning.roadmap-adoption.v1"));boolean found=false;long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
   while(!found&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))){
    if(!a.adoptionId().toString().equals(record.key()))continue;
    assertArrayEquals(expected,record.value());var env=RoadmapAdoptionEnvelopeV1.parseFrom(record.value());var p=RoadmapAdoptedV1.parseFrom(env.getPayload());
    assertEquals(fixture.space.toString(),env.getSpaceId());assertEquals(fixture.actor.toString(),env.getActorId());assertEquals(fixture.trace,env.getTraceId());
    assertEquals("PRIVATE",p.getVisibility());assertEquals(a.roadmapId().toString(),p.getRoadmapId());assertEquals(1,p.getSourceVersion());assertEquals(2,p.getNodesCount());assertEquals(1,p.getEdgesCount());
    for(var pair:Map.of("trace_id",fixture.trace,"schema_group","learning","schema_artifact","roadmap-adoption-events","schema_version","1").entrySet())assertEquals(pair.getValue(),new String(record.headers().lastHeader(pair.getKey()).value(),StandardCharsets.UTF_8));
    found=true;
   }assertTrue(found,"Exact adoption event did not reach CDC topic");
  }
 }
}
