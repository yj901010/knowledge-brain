package io.knowledgebrain.brainquery;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import io.knowledgebrain.contracts.knowledge.publicknowledge.v1.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PublicProjection {
 static final String TOPIC="knowledge.public-knowledge.v1",GROUP="brain-query-public-v1",INDEX="brain-public-knowledge-v1";
 static final Set<String> KINDS=Set.of("CONCEPT","ALIAS","CLAIM","SOURCE","EVIDENCE","MAPPING","VERIFICATION","QUALIFIER");
 static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 record Event(UUID eventId,UUID id,UUID space,String kind,long version,String state,String title,JsonNode content,JsonNode qualifierPins,UUID revision,String trace,String hash){}
 record Ref(String role,UUID target){}
 record Document(UUID id,UUID space,String kind,long canonicalVersion,long sequence,boolean visible,String title,String text,List<Ref> refs,String hash) {
  Map<String,Object> source(){return Map.of("entityId",id.toString(),"spaceId",space.toString(),"kind",kind,"canonicalVersion",canonicalVersion,"visibility","PUBLIC","state",visible?"ACTIVE":"RETIRED","title",title,"text",text,"snapshotHash",hash);}
 }
 static UUID id(String raw){UUID value=UUID.fromString(raw);if(value.version()!=7||value.variant()!=2||!value.toString().equals(raw))throw invalid();return value;}
 static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid public projection event");}
 static Object canonical(Object value){if(value instanceof JsonNode n){if(n.isObject()){var result=new TreeMap<String,Object>();for(var e:n.properties())result.put(e.getKey(),canonical(e.getValue()));return result;}if(n.isArray()){var result=new ArrayList<Object>();for(var child:n)result.add(canonical(child));return result;}if(n.isIntegralNumber())return n.asLong();if(n.isString())return n.asString();if(n.isBoolean())return n.asBoolean();if(n.isNull())return null;throw invalid();}if(value instanceof List<?> list)return list.stream().map(PublicProjection::canonical).toList();return value;}
 static Event decode(byte[] wire,String key,Map<String,String> headers){try{
  if(wire==null||wire.length>262144)throw invalid();var envelope=EventEnvelopeV1.parseFrom(wire);var p=PublicKnowledgeChangedPayloadV1.parseFrom(envelope.getPayload());
  UUID event=id(envelope.getEventId()),entity=id(envelope.getAggregateId()),space=id(envelope.getSpaceId());id(envelope.getActorId());
  if(!envelope.getProducer().equals("knowledge-service")||envelope.getEventVersion()!=1||envelope.getOccurredAtEpochMillis()<1||!envelope.getTraceId().matches("[0-9a-f]{32}")||envelope.getTraceId().equals("0".repeat(32))||!p.getEntityId().equals(entity.toString())||!key.equals(entity.toString())||!p.getVisibility().equals("PUBLIC")||!KINDS.contains(p.getKind())||p.getVersion()<1||p.getTitle().isBlank()||p.getTitle().codePointCount(0,p.getTitle().length())>200)throw invalid();
  if(headers!=null&&(!"knowledge".equals(headers.get("schema_group"))||!"public-knowledge-events".equals(headers.get("schema_artifact"))||!"1".equals(headers.get("schema_version"))||!envelope.getTraceId().equals(headers.get("trace_id"))))throw invalid();
  if(envelope.getEventType().equals("PublicKnowledgeProposalChanged")&&envelope.getAggregateType().equals("PublicKnowledgeProposal")&&Set.of("SUBMITTED","APPROVED","REJECTED","MERGED").contains(p.getState()))return null;
  if(!envelope.getEventType().equals("PublicKnowledgeChanged")||!envelope.getAggregateType().equals("PublicKnowledge")||!Set.of("ACTIVE","RETIRED").contains(p.getState()))throw invalid();
  var content=JSON.readTree(p.getContentJson());var pins=JSON.readTree(p.getQualifierPinsJson());if(content==null||!content.isObject()||pins==null||!pins.isObject()||pins.size()>100||p.getContentJson().getBytes(StandardCharsets.UTF_8).length>200000)throw invalid();
  for(var entry:pins.properties()){id(entry.getKey());if(!entry.getValue().isIntegralNumber()||!entry.getValue().canConvertToLong()||entry.getValue().asLong()<1)throw invalid();}
  // Unknown/private references never create vertices outside this public Space.
  for(var ref:references(p.getKind(),content))id(ref.target().toString());
  UUID revision=id(p.getRevisionId());String hash=hash(JSON.writeValueAsString(canonical(List.of(entity.toString(),space.toString(),p.getKind(),p.getVersion(),p.getState(),p.getTitle(),content,pins,revision.toString()))));
  return new Event(event,entity,space,p.getKind(),p.getVersion(),p.getState(),p.getTitle(),content,pins,revision,envelope.getTraceId(),hash);
 }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw invalid();}}
 static List<Ref> references(String kind,JsonNode c){var r=new ArrayList<Ref>();switch(kind){
  case "ALIAS"->r.add(new Ref("ALIAS_OF",id(c.path("conceptId").asString())));
  case "CLAIM"->{r.add(new Ref("SUBJECT",id(c.path("subjectId").asString())));r.add(new Ref("PREDICATE",id(c.path("predicateId").asString())));if(c.path("objectType").asString().equals("CONCEPT"))r.add(new Ref("OBJECT",id(c.path("objectValue").asString())));}
  case "EVIDENCE"->{r.add(new Ref("SUPPORTS",id(c.path("claimId").asString())));r.add(new Ref("SOURCE",id(c.path("sourceId").asString())));}
  case "MAPPING"->{r.add(new Ref("MAPS",id(c.path("conceptId").asString())));r.add(new Ref("SOURCE",id(c.path("sourceId").asString())));}
  case "VERIFICATION"->{r.add(new Ref("ASSESSES",id(c.path("claimId").asString())));r.add(new Ref("EVIDENCE",id(c.path("evidenceId").asString())));}
  case "QUALIFIER"->{r.add(new Ref("QUALIFIES",id(c.path("claimId").asString())));r.add(new Ref("QUALIFIER_PREDICATE",id(c.path("predicateId").asString())));if(c.path("valueType").asString().equals("CONCEPT"))r.add(new Ref("QUALIFIER_VALUE",id(c.path("value").asString())));}
  case "CONCEPT","SOURCE"->{}default->throw invalid();
 }return List.copyOf(r);}
}
