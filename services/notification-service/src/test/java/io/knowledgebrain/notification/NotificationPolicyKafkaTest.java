package io.knowledgebrain.notification;
import java.util.*;import java.time.*;import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;import org.flywaydb.core.Flyway;import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.kafka.clients.consumer.*;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.notification.policy.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_NOTIFICATION_KAFKA_BOOTSTRAP",matches=".+")
class NotificationPolicyKafkaTest {
 @Test void preferenceAndMissedDeliveryOutboxReachOwnLiteralTopicWithoutChangingOldContract()throws Exception{
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_NOTIFICATION_DATABASE_URL"));ds.setUser("brain_notification");ds.setPassword(System.getenv("BRAIN_TEST_NOTIFICATION_DATABASE_PASSWORD"));
  Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();var store=new NotificationStore(ds,Clock.systemUTC());
  var space=DispatchFixtures.ids.next();var actor=DispatchFixtures.ids.next();var source=DispatchFixtures.event(space,DispatchFixtures.ids.next());
  var off=store.preference(space,actor,0,false,"b".repeat(32));assertFalse(store.accept(source));var missed=store.suppressions(space,null,20).items().getFirst();store.preference(space,actor,1,true,"b".repeat(32));assertFalse(store.accept(source));
  var expected=new HashMap<String,byte[]>();new JdbcTemplate(ds).query("SELECT id,payload FROM outbox_event WHERE aggregateid IN (?,?)",(rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),off.preferenceId(),missed.suppressionId()).forEach(e->expected.put(e.getKey(),e.getValue()));assertEquals(3,expected.size());
  var properties=NotificationConfiguration.kafka(System.getenv("BRAIN_TEST_NOTIFICATION_KAFKA_BOOTSTRAP"),"/notification-kafka-test","notification-validation-policy-"+DispatchFixtures.ids.next());properties.put("auto.offset.reset","earliest");
  try(var consumer=new KafkaConsumer<String,byte[]>(properties)){
   consumer.subscribe(List.of("notification.delivery-policy.v1"));var found=new HashSet<String>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
   while(found.size()<3&&System.nanoTime()<deadline)for(var row:consumer.poll(Duration.ofSeconds(1))){
    if(!Set.of(off.preferenceId().toString(),missed.suppressionId().toString()).contains(row.key()))continue;
    var e=NotificationPolicyEnvelopeV1.parseFrom(row.value());assertArrayEquals(expected.get(e.getEventId()),row.value());assertEquals("notification-service",e.getProducer());assertEquals(space.toString(),e.getSpaceId());assertEquals(row.key(),e.getAggregateId());assertEquals(1,e.getEventVersion());
    boolean suppression=e.getEventType().equals("NotificationDeliverySuppressed");
    if(suppression){var p=NotificationSuppressionPayloadV1.parseFrom(e.getPayload());assertArrayEquals(source,p.getSourceDispatchEnvelope().toByteArray());assertEquals(1,p.getPreferenceVersion());assertEquals("PREFERENCE_DISABLED",p.getReason());assertEquals("PRIVATE",p.getVisibility());assertEquals("NotificationSuppression",e.getAggregateType());}
    else{var p=NotificationPreferencePayloadV1.parseFrom(e.getPayload());assertEquals(p.getVersion()==2,p.getInAppEnabled());assertEquals("NotificationPreference",e.getAggregateType());assertEquals(actor.toString(),e.getActorId());}
    for(var h:Map.of("id",e.getEventId(),"trace_id",suppression?"a".repeat(32):"b".repeat(32),"schema_group","notification","schema_artifact","delivery-policy-events","schema_version","1").entrySet())assertEquals(h.getValue(),new String(row.headers().lastHeader(h.getKey()).value(),StandardCharsets.UTF_8));found.add(e.getEventId());
   }
   assertEquals(expected.keySet(),found);
  }
 }
}
