package io.knowledgebrain.brainquery;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TypedClaimProjectionTest {
 static String id(int i){return "019a0000-0000-7000-8000-"+String.format("%012d",i);}
 static byte[] wire(String type,String value,String concept,long version,String state){
  var p=io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2.newBuilder().setClaimId(id(1)).setSubjectConceptId(id(2)).setPredicateConceptId(id(3)).setObjectType(type).setObjectValue(value).setObjectConceptId(concept).setRevisionId(id(4)).setVersion(version).setState(state).setVisibility("PRIVATE").setVerificationState("DRAFT").build();
  return io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2.newBuilder().setEventId(id(5)).setEventType(state.equals("RETIRED")?"ClaimRetired":version==1?"ClaimCreated":"ClaimUpdated").setEventVersion(2).setOccurredAtEpochMillis(1).setProducer("knowledge-service").setAggregateType("Claim").setAggregateId(id(1)).setSpaceId(id(6)).setActorId(id(7)).setTraceId("a".repeat(32)).setPayload(p.toByteString()).build().toByteArray();
 }
 @Test void exactLiteralProjectsTwoConceptEdgesAndFullSearchText(){
  var wire=wire("NUMBER","9007199254740993.123456789012345678","",1,"ACTIVE");assertEquals("knowledge.claim.v2",BrainSnapshotClient.topic("CLAIM",wire));var h=Map.of("id",id(5),"schema_group","knowledge","schema_artifact","claim-events-v2","schema_version","1","trace_id","a".repeat(32));
  var n=BrainProjection.decode("knowledge.claim.v2",wire,id(1),h);assertEquals(2,n.refs().size());assertEquals("NUMBER: 9007199254740993.123456789012345678",n.body());
  assertThrows(IllegalArgumentException.class,()->BrainProjection.decode("knowledge.claim.v1",wire));assertThrows(IllegalArgumentException.class,()->BrainProjection.decode("knowledge.claim.v2",wire,id(1),Map.of()));
  assertEquals("",BrainProjection.decode("knowledge.claim.v2",wire("STRING","private historical text","",2,"RETIRED")).body());
 }
 @Test void malformedOrNoncanonicalScalarEventsAreRejected(){
  for(var pair:List.of(new String[]{"NUMBER","1.00"},new String[]{"NUMBER","1e3"},new String[]{"BOOLEAN","False"},new String[]{"DATE","2025-02-29"},new String[]{"STRING"," "})){
   assertThrows(IllegalArgumentException.class,()->BrainProjection.decode("knowledge.claim.v2",wire(pair[0],pair[1],"",1,"ACTIVE")));
  }
  assertThrows(IllegalArgumentException.class,()->BrainProjection.decode("knowledge.claim.v2",wire("NUMBER","1",id(8),1,"ACTIVE")));
  assertThrows(IllegalArgumentException.class,()->BrainProjection.decode("knowledge.claim.v2",wire("CONCEPT","wrong",id(8),1,"ACTIVE")));
 }
 @Test void legacyAndNewConceptEventsHaveTheSameProjectionFingerprint()throws Exception{
  var wire=wire("CONCEPT","",id(8),1,"ACTIVE");var v2=io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2.parseFrom(wire);
  var old=io.knowledgebrain.contracts.knowledge.claim.v1.EventEnvelopeV1.parseFrom(v2.toByteArray()).toBuilder().setEventVersion(1).build();
  assertEquals("knowledge.claim.v1",BrainSnapshotClient.topic("CLAIM",old.toByteArray()));assertEquals(BrainProjection.decode("knowledge.claim.v1",old.toByteArray()).fingerprint(),BrainProjection.decode("knowledge.claim.v2",wire).fingerprint());
 }
}
