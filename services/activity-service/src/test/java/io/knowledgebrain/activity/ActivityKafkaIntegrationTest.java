package io.knowledgebrain.activity;
import java.util.*;import java.time.*;import java.nio.charset.StandardCharsets;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import org.apache.kafka.clients.consumer.*;import org.apache.kafka.common.TopicPartition;import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_ACTIVITY_KAFKA",matches=".+")
class ActivityKafkaIntegrationTest {
 // Broker retention may remove old kinds. Check metadata/ACLs for every configured
 // stream and the latest retained record on every nonempty partition. Freeze end
 // offsets so this read-only check is independent of concurrent application writes.
 @Test void actualRetainedCdcStreamsHaveTrustedImmutableEnvelopesAndScopedTlsRead()throws Exception{
  var p=ActivityConfiguration.kafka(System.getenv("BRAIN_TEST_ACTIVITY_KAFKA"),"/activity-kafka","activity-test-"+UUID.randomUUID());
  try(var c=new KafkaConsumer<String,byte[]>(p)){
   var partitions=new ArrayList<TopicPartition>();
   for(String topic:ActivityEvent.topics()){var metadata=c.partitionsFor(topic);assertFalse(metadata.isEmpty(),topic);for(var info:metadata)partitions.add(new TopicPartition(topic,info.partition()));}
   c.assign(partitions);var starts=c.beginningOffsets(partitions);var ends=c.endOffsets(partitions);var remaining=new HashSet<TopicPartition>();var expectedTopics=new HashSet<String>();
   for(var part:partitions){if(ends.get(part)>starts.get(part)){remaining.add(part);expectedTopics.add(part.topic());c.seek(part,ends.get(part)-1);}else{c.seek(part,ends.get(part));c.pause(List.of(part));}}
   assertFalse(remaining.isEmpty(),"At least one real CDC record required");var observed=new HashSet<String>();long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
   while(!remaining.isEmpty()&&System.nanoTime()<deadline){for(var record:c.poll(Duration.ofSeconds(1))){var part=new TopicPartition(record.topic(),record.partition());if(!remaining.contains(part)||record.offset()!=ends.get(part)-1)continue;var headers=new HashMap<String,String>();for(var h:record.headers())if(h.value()!=null)assertNull(headers.put(h.key(),new String(h.value(),StandardCharsets.UTF_8)));ActivityEvent.transport(record.topic(),record.value(),record.key(),headers);assertNotNull(ActivityEvent.decode(record.topic(),record.value()).eventId());observed.add(record.topic());remaining.remove(part);c.pause(List.of(part));}}
   assertEquals(Set.of(),remaining,"Every nonempty partition was checked");assertEquals(expectedTopics,observed);assertTrue(observed.contains("integration.github-commit.v1"),"The new stream must have real Integration CDC records");
  }
 }
}
