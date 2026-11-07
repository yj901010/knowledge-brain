package io.knowledgebrain.brainquery;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.content.links.v1.*;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class NoteGraphProjectionTest {
 private static String id(int n){return "019a0000-0000-7000-8000-"+String.format("%012x",n);}
 private static NoteLinksChangedPayloadV1.Builder payload(){return NoteLinksChangedPayloadV1.newBuilder().setSourceNoteId(id(1)).setNoteVersion(2).setLinkVersion(7).setSourceState("ACTIVE").setRevisionId(id(2));}
 private static WikiLinkV1.Builder link(String state){return WikiLinkV1.newBuilder().setLinkId(id(5)).setOrdinal(0).setStartOffset(0).setEndOffset(7).setTargetToken("Target").setStatus(state);}
 private static byte[] event(NoteLinksChangedPayloadV1.Builder payload){return LinkEventEnvelopeV1.newBuilder().setEventId(id(3)).setSpaceId(id(4)).setAggregateId(id(1)).setAggregateType("NoteLinks").setProducer("content-service").setEventType("NoteLinksChanged").setEventVersion(1).setPayload(ByteString.copyFrom(payload.build().toByteArray())).build().toByteArray();}
 @Test void keepsLinkVersionIndependentAndOnlyResolvedOccurrencesBecomeEdges(){
  var result=NoteGraphProjection.decode(event(payload().addLinks(link("RESOLVED").setTargetNoteId(id(6))).addLinks(link("UNRESOLVED").setLinkId(id(7)).setOrdinal(1))));
  assertEquals(2,result.noteVersion());assertEquals(7,result.linkVersion());assertEquals(1,result.resolved().size());assertEquals(UUID.fromString(id(6)),result.resolved().getFirst().targetNoteId());
  assertEquals(64,result.payloadHash().length());
 }
 @Test void retiredTargetAndAmbiguityDoNotCreateEdges(){
  assertTrue(NoteGraphProjection.decode(event(payload().addLinks(link("TARGET_RETIRED").setTargetNoteId(id(6))))).resolved().isEmpty());
  assertTrue(NoteGraphProjection.decode(event(payload().addLinks(link("AMBIGUOUS")))).resolved().isEmpty());
 }
 @Test void rejectsIncompleteBindingsDuplicateOccurrencesAndRetiredSourceEdges(){
  assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(event(payload().addLinks(link("RESOLVED")))));
  assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(event(payload().addLinks(link("UNRESOLVED").setTargetNoteId(id(6))))));
  assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(event(payload().addLinks(link("UNRESOLVED")).addLinks(link("UNRESOLVED").setOrdinal(1)))));
  assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(event(payload().setSourceState("RETIRED").addLinks(link("UNRESOLVED")))));
  assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(event(payload().setLinkVersion(-1))));
 }
 @Test void rejectsWrongWireContractAndOversizedInputWithoutPayloadInError(){
  byte[] bytes=LinkEventEnvelopeV1.newBuilder().setEventType("NoteUpdated").build().toByteArray();
  assertEquals("Invalid private graph event",assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(bytes)).getMessage());
  assertThrows(IllegalArgumentException.class,()->NoteGraphProjection.decode(new byte[524289]));
 }
}
