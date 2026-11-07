package io.knowledgebrain.brainquery;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import io.knowledgebrain.contracts.content.concepts.v1.*;

/** Validated existing immutable Protobuf snapshots. No title/body survives a tombstone. */
final class BrainProjection {
 static final List<String> TOPICS=List.of("content.note.v1","content.note-links.v1","content.note-concepts.v1","knowledge.concept.v1","knowledge.claim.v1","content.bookmark.v1","content.question.v1","content.decision.v1","content.record-concepts.v1","knowledge.claim.v2");
 static final Set<String> RECORDS=Set.of("BOOKMARK","QUESTION","DECISION");
 static final List<String> KINDS=List.of("CONCEPT","CLAIM","NOTE","LINKS","NOTE_CONCEPTS","BOOKMARK","QUESTION","DECISION","BOOKMARK_CONCEPTS","QUESTION_CONCEPTS","DECISION_CONCEPTS");
 record Ref(UUID id,String role,String targetKind,UUID targetId){}
 record Input(UUID eventId,UUID entityId,UUID spaceId,String kind,long version,String state,String visibility,String title,String body,List<Ref> refs,String traceId,UUID aggregateId,long recordVersion){
  Input{refs=List.copyOf(refs);if(aggregateId==null)aggregateId=entityId;}
  Input(UUID eventId,UUID entityId,UUID spaceId,String kind,long version,String state,String visibility,String title,String body,List<Ref> refs,String traceId){this(eventId,entityId,spaceId,kind,version,state,visibility,title,body,refs,traceId,entityId,0);}
  String owner(){return Set.of("LINKS","NOTE_CONCEPTS").contains(kind)?"NOTE":kind.endsWith("_CONCEPTS")?kind.substring(0,kind.length()-9):kind;}
  String fingerprint(){var fields=new ArrayList<String>(List.of(entityId.toString(),spaceId.toString(),kind,""+version,state,visibility,title,body,aggregateId.toString(),""+recordVersion));for(var r:refs)fields.addAll(List.of(r.id().toString(),r.role(),r.targetKind(),r.targetId().toString()));return hash(fields);}
 }
 static UUID id(String s){return NoteGraphProjection.id(s);}
 static String hash(List<String> fields){try{var digest=MessageDigest.getInstance("SHA-256");for(var s:fields){var b=s.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());digest.update(b);}return HexFormat.of().formatHex(digest.digest());}catch(Exception e){throw new IllegalStateException("Projection digest unavailable");}}
 static void text(String s,int bytes){if(s==null||s.getBytes(StandardCharsets.UTF_8).length>bytes||s.indexOf(0)>=0||s.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw invalid();}
 static void envelope(String producer,String aggregate,String expectedProducer,String expectedAggregate,int version,long occurred,String actor,String trace){if(!producer.equals(expectedProducer)||!aggregate.equals(expectedAggregate)||version!=1||occurred<=0||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw invalid();id(actor);}
 static Input decode(String topic,byte[] wire,String key,Map<String,String> headers){
  var n=decode(topic,wire);String group=topic.startsWith("knowledge.")?"knowledge":"content",artifact=switch(n.kind()){case "NOTE"->"note-events";case "LINKS"->"note-links-events";case "NOTE_CONCEPTS"->"note-concepts-events";case "CONCEPT"->"concept-events";case "CLAIM"->topic.equals("knowledge.claim.v2")?"claim-events-v2":"claim-events";case "BOOKMARK"->"bookmark-events";case "QUESTION"->"question-events";case "DECISION"->"decision-events";case "BOOKMARK_CONCEPTS","QUESTION_CONCEPTS","DECISION_CONCEPTS"->"record-concepts-events";default->throw invalid();};
  if(!n.aggregateId().toString().equals(key)||!n.eventId().toString().equals(headers.get("id"))||!group.equals(headers.get("schema_group"))||!artifact.equals(headers.get("schema_artifact"))||!"1".equals(headers.get("schema_version"))||!n.traceId().equals(headers.get("trace_id")))throw invalid();return n;
 }
 static Input decode(String topic,byte[] wire){
  if(wire==null||wire.length==0||wire.length>524288||!TOPICS.contains(topic))throw invalid();
  try{
   if(topic.equals(TOPICS.get(0))){var n=NoteSearchProjection.decode(wire);var e=io.knowledgebrain.contracts.content.v1.EventEnvelopeV1.parseFrom(wire);envelope(e.getProducer(),e.getAggregateType(),"content-service","Note",e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());text(n.title(),800);text(n.markdown(),32768);return new Input(n.eventId(),n.noteId(),n.spaceId(),"NOTE",n.version(),n.state(),"PRIVATE",n.title(),n.markdown(),List.of(),e.getTraceId());}
   if(topic.equals(TOPICS.get(1))){var n=NoteGraphProjection.decode(wire);var e=io.knowledgebrain.contracts.content.links.v1.LinkEventEnvelopeV1.parseFrom(wire);envelope(e.getProducer(),e.getAggregateType(),"content-service","NoteLinks",e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());return new Input(n.eventId(),n.sourceNoteId(),n.spaceId(),"LINKS",n.linkVersion(),n.state(),"PRIVATE","","",n.resolved().stream().map(r->new Ref(r.linkId(),"WIKI","NOTE",r.targetNoteId())).toList(),e.getTraceId());}
   if(topic.equals(TOPICS.get(2))){var e=ConceptEventEnvelopeV1.parseFrom(wire);envelope(e.getProducer(),e.getAggregateType(),"content-service","NoteConceptLinks",e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());if(!e.getEventType().equals("NoteConceptsChanged"))throw invalid();var p=NoteConceptsChangedPayloadV1.parseFrom(e.getPayload());var entity=id(p.getNoteId());id(p.getRevisionId());if(!entity.equals(id(e.getAggregateId()))||p.getConceptVersion()<1||p.getNoteVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getNoteState())||p.getLinksCount()>20||p.getNoteState().equals("RETIRED")&&p.getLinksCount()!=0)throw invalid();var refs=new ArrayList<Ref>();var targets=new HashSet<UUID>();var ids=new HashSet<UUID>();for(var r:p.getLinksList()){var target=id(r.getConceptId());var rid=id(r.getLinkId());if(!targets.add(target)||!ids.add(rid))throw invalid();refs.add(new Ref(rid,"NOTE_CONCEPT","CONCEPT",target));}return new Input(id(e.getEventId()),entity,id(e.getSpaceId()),"NOTE_CONCEPTS",p.getConceptVersion(),p.getNoteState(),"PRIVATE","","",refs,e.getTraceId());}
   if(topic.equals(TOPICS.get(3))){var e=io.knowledgebrain.contracts.knowledge.v1.EventEnvelopeV1.parseFrom(wire);envelope(e.getProducer(),e.getAggregateType(),"knowledge-service","Concept",e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());var p=io.knowledgebrain.contracts.knowledge.v1.ConceptChangedPayloadV1.parseFrom(e.getPayload());var entity=id(p.getConceptId());id(p.getRevisionId());if(!entity.equals(id(e.getAggregateId()))||p.getVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getState())||!Set.of("PRIVATE","PUBLIC").contains(p.getVisibility())||!Set.of("ConceptCreated","ConceptUpdated","ConceptRetired").contains(e.getEventType())||e.getEventType().equals("ConceptRetired")!=p.getState().equals("RETIRED")||e.getEventType().equals("ConceptCreated")&&p.getVersion()!=1||p.getTitle().isBlank()||p.getTitle().codePointCount(0,p.getTitle().length())>200)throw invalid();text(p.getTitle(),800);text(p.getDescription(),32768);boolean visible=p.getState().equals("ACTIVE")&&p.getVisibility().equals("PRIVATE");return new Input(id(e.getEventId()),entity,id(e.getSpaceId()),"CONCEPT",p.getVersion(),p.getState(),p.getVisibility(),visible?p.getTitle():"",visible?p.getDescription():"",List.of(),e.getTraceId());}
   if(topic.startsWith("content.")&&!topic.startsWith("content.note"))return RecordNetworkProjection.decode(topic,wire);
   if(topic.equals("knowledge.claim.v2"))return typedClaim(wire);
   var e=io.knowledgebrain.contracts.knowledge.claim.v1.EventEnvelopeV1.parseFrom(wire);envelope(e.getProducer(),e.getAggregateType(),"knowledge-service","Claim",e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());var p=io.knowledgebrain.contracts.knowledge.claim.v1.ClaimChangedPayloadV1.parseFrom(e.getPayload());var entity=id(p.getClaimId());id(p.getRevisionId());if(!entity.equals(id(e.getAggregateId()))||p.getVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getState())||!p.getVisibility().equals("PRIVATE")||!p.getVerificationState().equals("DRAFT")||!p.getObjectType().equals("CONCEPT")||!Set.of("ClaimCreated","ClaimUpdated","ClaimRetired").contains(e.getEventType())||e.getEventType().equals("ClaimRetired")!=p.getState().equals("RETIRED")||e.getEventType().equals("ClaimCreated")&&p.getVersion()!=1)throw invalid();var refs=List.of(new Ref(entity,"SUBJECT","CONCEPT",id(p.getSubjectConceptId())),new Ref(entity,"PREDICATE","CONCEPT",id(p.getPredicateConceptId())),new Ref(entity,"OBJECT","CONCEPT",id(p.getObjectConceptId())));return new Input(id(e.getEventId()),entity,id(e.getSpaceId()),"CLAIM",p.getVersion(),p.getState(),"PRIVATE","","",refs,e.getTraceId());
  }catch(com.google.protobuf.InvalidProtocolBufferException e){throw invalid();}
 }
 static Input typedClaim(byte[] wire)throws com.google.protobuf.InvalidProtocolBufferException {
  var e=io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2.parseFrom(wire);
  if(e.getEventVersion()!=2)throw invalid();
  envelope(e.getProducer(),e.getAggregateType(),"knowledge-service","Claim",1,e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());
  var p=io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2.parseFrom(e.getPayload());
  var entity=id(p.getClaimId());id(p.getRevisionId());
  if(!entity.equals(id(e.getAggregateId()))||p.getVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getState())||!p.getVisibility().equals("PRIVATE")||!p.getVerificationState().equals("DRAFT")||!Set.of("ClaimCreated","ClaimUpdated","ClaimRetired").contains(e.getEventType())||e.getEventType().equals("ClaimRetired")!=p.getState().equals("RETIRED")||e.getEventType().equals("ClaimCreated")&&p.getVersion()!=1)throw invalid();
  var refs=new ArrayList<Ref>();refs.add(new Ref(entity,"SUBJECT","CONCEPT",id(p.getSubjectConceptId())));refs.add(new Ref(entity,"PREDICATE","CONCEPT",id(p.getPredicateConceptId())));
  String body="";
  if(p.getObjectType().equals("CONCEPT")){if(!p.getObjectValue().isEmpty())throw invalid();refs.add(new Ref(entity,"OBJECT","CONCEPT",id(p.getObjectConceptId())));}
  else {if(!p.getObjectConceptId().isEmpty())throw invalid();scalar(p.getObjectType(),p.getObjectValue());if(p.getState().equals("ACTIVE"))body=p.getObjectType()+": "+p.getObjectValue();}
  return new Input(id(e.getEventId()),entity,id(e.getSpaceId()),"CLAIM",p.getVersion(),p.getState(),"PRIVATE","",body,refs,e.getTraceId());
 }
 static void scalar(String type,String value){
  text(value,4000);
  try{switch(type){
   case "STRING"->{if(value.isBlank()||!value.equals(value.strip())||value.codePointCount(0,value.length())>1000)throw invalid();}
   case "NUMBER"->{if(value.length()>128||!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))throw invalid();var n=new java.math.BigDecimal(value).stripTrailingZeros();if(n.precision()>38||n.scale()>18||n.precision()-n.scale()>38||!value.equals(n.signum()==0?"0":n.toPlainString()))throw invalid();}
   case "DATE"->{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||value.startsWith("0000")||!java.time.LocalDate.parse(value).toString().equals(value))throw invalid();}
   case "BOOLEAN"->{if(!Set.of("true","false").contains(value))throw invalid();}
   default->throw invalid();
  }}catch(RuntimeException e){throw invalid();}
 }
 static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid unified projection input");}
}
