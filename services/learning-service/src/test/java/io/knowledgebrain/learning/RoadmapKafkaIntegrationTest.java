package io.knowledgebrain.learning;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import io.knowledgebrain.contracts.learning.roadmap.v1.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP",matches=".+")
class RoadmapKafkaIntegrationTest {
 @Test void committedGraphsAndRetirementReachKafkaWithExactBytesAndTracing()throws Exception {
  var ds=new PGSimpleDataSource();ds.setURL(System.getenv("BRAIN_TEST_LEARNING_DATABASE_URL"));ds.setUser("brain_learning");ds.setPassword(System.getenv("BRAIN_TEST_LEARNING_DATABASE_PASSWORD"));Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
  var store=new RoadmapStore(ds,Clock.systemUTC());var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();String trace="e".repeat(32);var root=store.create(space,actor,"Kafka roadmap","",trace);
  var graph=store.graph(space,root.roadmapId(),actor,1,new RoadmapGraph.Edit(List.of(new RoadmapGraph.NodeEdit("new:a","First",""),new RoadmapGraph.NodeEdit("new:b","Second","")),List.of(new RoadmapGraph.EdgeEdit("new:e","PREREQUISITE_OF","new:a","new:b"))),trace);
  assertThrows(ResponseStatusException.class,()->store.metadata(space,root.roadmapId(),actor,1,"Stale","",false,trace));store.metadata(space,root.roadmapId(),actor,2,"Edited","",false,trace);store.metadata(space,root.roadmapId(),actor,3,null,null,true,trace);
  var expected=new HashMap<String,byte[]>();new JdbcTemplate(ds).query("SELECT id,payload FROM outbox_event WHERE aggregateid=?",(rs,n)->Map.entry(rs.getObject(1,UUID.class).toString(),rs.getBytes(2)),root.roadmapId()).forEach(r->expected.put(r.getKey(),r.getValue()));assertEquals(4,expected.size());
  var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_LEARNING_KAFKA_BOOTSTRAP"));options.put("group.id","learning-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/learning-kafka-test/ca.crt")));options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/learning-kafka-test/user.crt")));options.put("ssl.keystore.key",Files.readString(Path.of("/learning-kafka-test/user.key")));
  try(var consumer=new KafkaConsumer<String,byte[]>(options)) {
   consumer.subscribe(List.of("learning.roadmap.v1"));var found=new HashSet<String>();var versions=new ArrayList<Long>();var types=new ArrayList<String>();long deadline=System.nanoTime()+Duration.ofSeconds(60).toNanos();
   while(found.size()<4&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))) {
    if(!root.roadmapId().toString().equals(record.key()))continue;var event=RoadmapEventEnvelopeV1.parseFrom(record.value());var payload=RoadmapChangedPayloadV1.parseFrom(event.getPayload());assertArrayEquals(expected.get(event.getEventId()),record.value());assertEquals("learning-service",event.getProducer());assertEquals("Roadmap",event.getAggregateType());assertEquals(root.roadmapId().toString(),event.getAggregateId());assertEquals(space.toString(),event.getSpaceId());assertEquals(actor.toString(),event.getActorId());assertEquals(trace,event.getTraceId());assertEquals(1,event.getEventVersion());assertEquals(7,UUID.fromString(event.getEventId()).version());assertEquals(7,UUID.fromString(payload.getRevisionId()).version());assertEquals("PRIVATE",payload.getVisibility());assertEquals(payload.getVersion()==4?"RETIRED":"ACTIVE",payload.getState());assertEquals(payload.getVersion()>=3?"Edited":"Kafka roadmap",payload.getTitle());assertEquals(payload.getVersion()==1?0:2,payload.getNodesCount());assertEquals(payload.getVersion()==1?0:1,payload.getEdgesCount());if(payload.getVersion()>1){assertEquals(graph.edges().getFirst().edgeId().toString(),payload.getEdges(0).getEdgeId());assertEquals("PREREQUISITE_OF",payload.getEdges(0).getKind());}
    for(var pair:Map.of("trace_id",trace,"schema_group","learning","schema_artifact","roadmap-events","schema_version","1").entrySet()){var h=record.headers().lastHeader(pair.getKey());assertNotNull(h);assertEquals(pair.getValue(),new String(h.value(),StandardCharsets.UTF_8));}
    if(found.add(event.getEventId())){versions.add(payload.getVersion());types.add(event.getEventType());}
   }
   assertEquals(expected.keySet(),found);assertEquals(List.of(1L,2L,3L,4L),versions);assertEquals(List.of("RoadmapCreated","RoadmapGraphChanged","RoadmapUpdated","RoadmapRetired"),types);
  }
 }
}
