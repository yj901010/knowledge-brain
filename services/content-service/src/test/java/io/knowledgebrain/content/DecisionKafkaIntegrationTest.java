package io.knowledgebrain.content;
import java.time.*;import java.nio.file.*;import java.util.*;import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.*;import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;import org.postgresql.ds.PGSimpleDataSource;import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.content.decision.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_KAFKA_BOOTSTRAP",matches=".+")
class DecisionKafkaIntegrationTest {
 @Test void ownImmutableDecisionRevisionsReachExactTopicWithStableBytesAndTraceHeaders()throws Exception{
  var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
  var store=new DecisionStore(source,Clock.systemUTC());var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();var key=UUID.randomUUID().toString().replace("-","");String trace="e".repeat(32);
  var input=new DecisionStore.Input("Private decision"," \n\tverbatim context\r\n ","","Draft rationale","Option B","DRAFT");var v=store.create(space,actor,key,input,trace).decision();store.change(space,v.decisionId(),actor,1,new DecisionStore.Input("Updated",input.context(),"Direct outcome","Explicit rationale","Option B","DECIDED"),false,trace);store.change(space,v.decisionId(),actor,2,null,true,trace);assertEquals(v,store.create(space,actor,key,input,trace).decision());
  var expected=new HashMap<String,byte[]>();new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregateid=? AND aggregatetype='decision'",(rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),v.decisionId()).forEach(e->expected.put(e.getKey(),e.getValue()));assertEquals(3,expected.size());
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_CONTENT_KAFKA_BOOTSTRAP"));options.put("group.id","content-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/content-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/content-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/content-kafka-test/user.key")));

  try(var consumer=new KafkaConsumer<String,byte[]>(options)){
   consumer.subscribe(List.of("content.decision.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
   while(found.size()<3&&System.nanoTime()<deadline)for(var row:consumer.poll(Duration.ofSeconds(1))){
    if(!v.decisionId().toString().equals(row.key()))continue;var e=DecisionEventEnvelopeV1.parseFrom(row.value());assertArrayEquals(expected.get(e.getEventId()),row.value());assertEquals(space.toString(),e.getSpaceId());assertEquals(actor.toString(),e.getActorId());assertEquals("content-service",e.getProducer());assertEquals("Decision",e.getAggregateType());assertEquals(1,e.getEventVersion());
    var payload=DecisionChangedPayloadV1.parseFrom(e.getPayload());assertEquals(input.context(),payload.getContext());assertEquals(payload.getVersion()==1?"":"Direct outcome",payload.getOutcome());assertEquals(payload.getVersion()==1?"DRAFT":"DECIDED",payload.getStatus());assertEquals(payload.getVersion()==1?"Draft rationale":"Explicit rationale",payload.getRationale());assertEquals("Option B",payload.getAlternatives());assertEquals("PRIVATE",payload.getVisibility());assertEquals(payload.getVersion()==3?"RETIRED":"ACTIVE",payload.getState());
    for(var header:Map.of("id",e.getEventId(),"trace_id",trace,"schema_group","content","schema_artifact","decision-events","schema_version","1").entrySet())assertEquals(header.getValue(),new String(row.headers().lastHeader(header.getKey()).value(),StandardCharsets.UTF_8));
    if(found.add(e.getEventId()))versions.add(payload.getVersion());
   }
   assertEquals(expected.keySet(),found);assertEquals(List.of(1L,2L,3L),versions);
  }
 }
}
