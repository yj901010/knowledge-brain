package io.knowledgebrain.knowledge;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP",matches=".+")
class TypedClaimKafkaIntegrationTest {
 @Test void literalRevisionPinsSurviveAllThreeRealCdcStreams()throws Exception{
  var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_URL"));source.setUser("brain_knowledge");source.setPassword(System.getenv("BRAIN_TEST_KNOWLEDGE_DATABASE_PASSWORD"));
  var clock=Clock.systemUTC();var concepts=new ConceptStore(source,clock);var claims=new ClaimStore(source,clock);var sources=new SourceStore(source,clock);var citations=new CitationStore(source,clock);var qualifiers=new QualifierStore(source,clock);
  var ids=new UuidV7(clock);var space=ids.next();var actor=ids.next();String trace="d".repeat(32),exact="9007199254740993.123456789012345678";
  var subject=concepts.create(space,actor,"Literal CDC subject","",trace).conceptId();var predicate=concepts.create(space,actor,"Literal CDC predicate","",trace).conceptId();
  var c=claims.create(space,subject,actor,predicate,new ClaimStore.ObjectValue("NUMBER",null,exact),trace);
  var s=sources.create(space,actor,"Literal CDC Source",null,"Evidence","",trace);
  var ci=citations.create(space,subject,c.claimId(),actor,CitationStore.reference(s.sourceId(),1,1),trace);
  var q=qualifiers.create(space,subject,c.claimId(),actor,QualifierStore.edit(predicate,QualifierStore.value("STRING","scope"),1),trace);
  claims.change(space,subject,c.claimId(),actor,1,predicate,new ClaimStore.ObjectValue("DATE",null,"2026-10-20"),false,trace);
  citations.change(space,subject,c.claimId(),ci.citationId(),actor,1,CitationStore.reference(s.sourceId(),1,2),false,trace);
  qualifiers.change(space,subject,c.claimId(),q.qualifierId(),actor,1,QualifierStore.edit(predicate,QualifierStore.value("STRING","scope"),2),false,trace);
  citations.change(space,subject,c.claimId(),ci.citationId(),actor,2,null,true,trace);
  qualifiers.change(space,subject,c.claimId(),q.qualifierId(),actor,2,null,true,trace);
  claims.change(space,subject,c.claimId(),actor,2,null,null,true,trace);
  var expected=new HashMap<String,byte[]>();new JdbcTemplate(source).query("SELECT id,payload FROM outbox_event WHERE aggregateid IN (?,?,?)",rs->{expected.put(rs.getObject(1).toString(),rs.getBytes(2));},c.claimId(),ci.citationId(),q.qualifierId());assertThat(expected).hasSize(9);
        var options=new Properties();options.put("bootstrap.servers",System.getenv("BRAIN_TEST_KNOWLEDGE_KAFKA_BOOTSTRAP"));
        options.put("group.id","knowledge-validation-"+ids.next());options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of("/knowledge-kafka-test/ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of("/knowledge-kafka-test/user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of("/knowledge-kafka-test/user.key")));

  try(var consumer=new KafkaConsumer<String,byte[]>(options)){
   consumer.subscribe(List.of("knowledge.claim.v2","knowledge.citation.v2","knowledge.qualifier.v2"));var seen=new HashSet<String>();long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
   while(seen.size()<9&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofSeconds(1))){
    var envelope=io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2.parseFrom(record.value());if(!expected.containsKey(envelope.getEventId()))continue;
    assertThat(record.value()).isEqualTo(expected.get(envelope.getEventId()));assertThat(envelope.getEventVersion()).isEqualTo(2);assertThat(envelope.getAggregateId()).isEqualTo(record.key());
    String kind=record.topic().split("\\.")[1],type,value,objectId;long version;
    if(kind.equals("claim")){var v=io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2.parseFrom(envelope.getPayload());type=v.getObjectType();value=v.getObjectValue();objectId=v.getObjectConceptId();version=v.getVersion();}
    else if(kind.equals("citation")){var v=io.knowledgebrain.contracts.knowledge.citation.v2.ClaimCitationChangedPayloadV2.parseFrom(envelope.getPayload());type=v.getObjectType();value=v.getObjectValue();objectId=v.getObjectConceptId();version=v.getClaimVersion();}
    else{var v=io.knowledgebrain.contracts.knowledge.qualifier.v2.ClaimQualifierChangedPayloadV2.parseFrom(envelope.getPayload());type=v.getClaimObjectType();value=v.getClaimObjectValue();objectId=v.getClaimObjectConceptId();version=v.getClaimVersion();}
    assertThat(type).isEqualTo(version==1?"NUMBER":"DATE");assertThat(value).isEqualTo(version==1?exact:"2026-10-20");assertThat(objectId).isEmpty();
    assertThat(new String(record.headers().lastHeader("schema_artifact").value(),StandardCharsets.UTF_8)).isEqualTo(kind+"-events-v2");seen.add(envelope.getEventId());
   }
   assertThat(seen).containsExactlyInAnyOrderElementsOf(expected.keySet());
  }
 }
}
