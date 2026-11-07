package io.knowledgebrain.content;
import java.time.*;import java.nio.file.*;import java.util.*;import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.*;import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;import org.postgresql.ds.PGSimpleDataSource;import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;import static org.junit.jupiter.api.Assertions.*;
import io.knowledgebrain.contracts.content.recordconcepts.v1.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_CONTENT_KAFKA_BOOTSTRAP",matches=".+")
class RecordConceptKafkaIntegrationTest {
 @Test void threeTypedSourcesReachExactTopicWithImmutableAssociationRevisionsAndTraceHeaders()throws Exception{
  var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_CONTENT_DATABASE_URL"));source.setUser("brain_content");source.setPassword(System.getenv("BRAIN_TEST_CONTENT_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
  var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();String trace="e".repeat(32);var links=new RecordConceptStore(source,Clock.systemUTC());var a=ids.next();var b=ids.next();var associations=new HashMap<String,String>();
  for(var kind:RecordConceptStore.Kind.values()){
   String key=UUID.randomUUID().toString().replace("-","");UUID record=switch(kind){case BOOKMARK->new BookmarkStore(source,Clock.systemUTC()).create(space,actor,key,new BookmarkStore.Input("Bookmark Kafka","https://example.test/","memo"),trace).bookmark().bookmarkId();case QUESTION->new QuestionStore(source,Clock.systemUTC()).create(space,actor,key,new QuestionStore.Input("Question Kafka","context","","OPEN"),trace).question().questionId();case DECISION->new DecisionStore(source,Clock.systemUTC()).create(space,actor,key,new DecisionStore.Input("Decision Kafka","context","choice","reason","alternative","DECIDED"),trace).decision().decisionId();};
   var v=links.replace(space,kind,record,actor,0,List.of(a,b),trace);associations.put(v.links().associationId().toString(),kind.name()+"|"+record);links.replace(space,kind,record,actor,1,List.of(b,a),trace);links.replace(space,kind,record,actor,2,List.of(),trace);
  }
  var expected=new HashMap<String,byte[]>();new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregatetype='record-concepts' AND aggregateid IN (?,?,?)",(rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),associations.keySet().stream().map(UUID::fromString).toArray()).forEach(e->expected.put(e.getKey(),e.getValue()));assertEquals(9,expected.size());
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_CONTENT_KAFKA_BOOTSTRAP"));options.put("group.id","content-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/content-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/content-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/content-kafka-test/user.key")));

  try(var consumer=new KafkaConsumer<String,byte[]>(options)){
   consumer.subscribe(List.of("content.record-concepts.v1"));var found=new HashSet<String>();var versions=new HashMap<String,List<Long>>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
   while(found.size()<9&&System.nanoTime()<deadline)for(var row:consumer.poll(Duration.ofSeconds(1))){
    if(!associations.containsKey(row.key()))continue;var e=RecordConceptEventEnvelopeV1.parseFrom(row.value());assertArrayEquals(expected.get(e.getEventId()),row.value());assertEquals(space.toString(),e.getSpaceId());assertEquals(actor.toString(),e.getActorId());assertEquals("content-service",e.getProducer());assertEquals("RecordConceptLinks",e.getAggregateType());assertEquals("RecordConceptsChanged",e.getEventType());assertEquals(row.key(),e.getAggregateId());assertEquals(1,e.getEventVersion());assertEquals(trace,e.getTraceId());
    var p=RecordConceptsChangedPayloadV1.parseFrom(e.getPayload());assertEquals(associations.get(row.key()),p.getRecordType()+"|"+p.getRecordId());assertEquals(row.key(),p.getAssociationId());assertEquals(1,p.getRecordVersion());assertEquals("ACTIVE",p.getRecordState());assertEquals(7,UUID.fromString(p.getRevisionId()).version());assertEquals(p.getVersion()==3?List.of():p.getVersion()==1?List.of(a.toString(),b.toString()):List.of(b.toString(),a.toString()),p.getLinksList().stream().map(RecordConceptLinkV1::getConceptId).toList());
    for(var h:Map.of("id",e.getEventId(),"trace_id",trace,"schema_group","content","schema_artifact","record-concepts-events","schema_version","1").entrySet())assertEquals(h.getValue(),new String(row.headers().lastHeader(h.getKey()).value(),StandardCharsets.UTF_8));if(found.add(e.getEventId()))versions.computeIfAbsent(row.key(),k->new ArrayList<>()).add(p.getVersion());
   }assertEquals(expected.keySet(),found);assertEquals(3,versions.size());versions.values().forEach(v->assertEquals(List.of(1L,2L,3L),v));
  }
 }
}
