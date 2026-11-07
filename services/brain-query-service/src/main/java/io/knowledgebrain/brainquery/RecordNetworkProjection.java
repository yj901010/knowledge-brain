package io.knowledgebrain.brainquery;
import java.util.*;
import java.time.Instant;
import java.net.URI;
import com.google.protobuf.InvalidProtocolBufferException;

/** Frozen Content contracts; record bodies and association revisions advance independently. */
final class RecordNetworkProjection {
 static BrainProjection.Input decode(String topic,byte[] wire)throws InvalidProtocolBufferException {
  if(topic.equals("content.record-concepts.v1")){
   var e=io.knowledgebrain.contracts.content.recordconcepts.v1.RecordConceptEventEnvelopeV1.parseFrom(wire);
   BrainProjection.envelope(e.getProducer(),e.getAggregateType(),"content-service","RecordConceptLinks",e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getActorId(),e.getTraceId());
   var p=io.knowledgebrain.contracts.content.recordconcepts.v1.RecordConceptsChangedPayloadV1.parseFrom(e.getPayload());
   UUID aggregate=BrainProjection.id(p.getAssociationId()),entity=BrainProjection.id(p.getRecordId());BrainProjection.id(p.getRevisionId());
   if(!aggregate.equals(BrainProjection.id(e.getAggregateId()))||!e.getEventType().equals("RecordConceptsChanged")||!BrainProjection.RECORDS.contains(p.getRecordType())||p.getVersion()<1||p.getRecordVersion()<1||!p.getRecordState().equals("ACTIVE")||p.getLinksCount()>20)throw BrainProjection.invalid();
   var refs=new ArrayList<BrainProjection.Ref>();var ids=new HashSet<UUID>();var targets=new HashSet<UUID>();
   for(var v:p.getLinksList()){var id=BrainProjection.id(v.getLinkId());var target=BrainProjection.id(v.getConceptId());if(!ids.add(id)||!targets.add(target))throw BrainProjection.invalid();refs.add(new BrainProjection.Ref(id,"RECORD_CONCEPT","CONCEPT",target));}
   return new BrainProjection.Input(BrainProjection.id(e.getEventId()),entity,BrainProjection.id(e.getSpaceId()),p.getRecordType()+"_CONCEPTS",p.getVersion(),"ACTIVE","PRIVATE","","",refs,e.getTraceId(),aggregate,p.getRecordVersion());
  }
  if(topic.equals("content.bookmark.v1")){
   var e=io.knowledgebrain.contracts.content.bookmark.v1.BookmarkEventEnvelopeV1.parseFrom(wire);var p=io.knowledgebrain.contracts.content.bookmark.v1.BookmarkChangedPayloadV1.parseFrom(e.getPayload());
   BrainProjection.text(p.getUrl(),2048);BrainProjection.text(p.getMemo(),32768);
   try{var u=URI.create(p.getUrl());if(!p.getUrl().matches("[\\x21-\\x7e]+")||!Set.of("http","https").contains(Optional.ofNullable(u.getScheme()).orElse("").toLowerCase(Locale.ROOT))||u.getHost()==null||u.getRawUserInfo()!=null||u.getPort()>65535||u.getPort()==0||!u.getRawAuthority().equals(u.getHost()+(u.getPort()<0?"":":"+u.getPort())))throw BrainProjection.invalid();}catch(IllegalArgumentException x){throw BrainProjection.invalid();}
   return source("Bookmark",p.getBookmarkId(),p.getRevisionId(),p.getVersion(),p.getState(),p.getVisibility(),p.getTitle(),p.getUrl()+"\n"+p.getMemo(),p.getCreatedAt(),p.getUpdatedAt(),e.getEventId(),e.getAggregateId(),e.getSpaceId(),e.getActorId(),e.getProducer(),e.getAggregateType(),e.getEventType(),e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getTraceId());
  }
  if(topic.equals("content.question.v1")){
   var e=io.knowledgebrain.contracts.content.question.v1.QuestionEventEnvelopeV1.parseFrom(wire);var p=io.knowledgebrain.contracts.content.question.v1.QuestionChangedPayloadV1.parseFrom(e.getPayload());
   BrainProjection.text(p.getContext(),16384);BrainProjection.text(p.getAnswer(),16384);if(!Set.of("OPEN","ANSWERED").contains(p.getStatus())||p.getStatus().equals("ANSWERED")&&p.getAnswer().isBlank())throw BrainProjection.invalid();
   return source("Question",p.getQuestionId(),p.getRevisionId(),p.getVersion(),p.getState(),p.getVisibility(),p.getTitle(),p.getContext()+"\n"+p.getAnswer()+"\n"+p.getStatus(),p.getCreatedAt(),p.getUpdatedAt(),e.getEventId(),e.getAggregateId(),e.getSpaceId(),e.getActorId(),e.getProducer(),e.getAggregateType(),e.getEventType(),e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getTraceId());
  }
  if(!topic.equals("content.decision.v1"))throw BrainProjection.invalid();
  var e=io.knowledgebrain.contracts.content.decision.v1.DecisionEventEnvelopeV1.parseFrom(wire);var p=io.knowledgebrain.contracts.content.decision.v1.DecisionChangedPayloadV1.parseFrom(e.getPayload());
  for(var text:List.of(p.getContext(),p.getOutcome(),p.getRationale(),p.getAlternatives()))BrainProjection.text(text,8192);if(!Set.of("DRAFT","DECIDED").contains(p.getStatus())||p.getStatus().equals("DECIDED")&&(p.getOutcome().isBlank()||p.getRationale().isBlank()))throw BrainProjection.invalid();
  return source("Decision",p.getDecisionId(),p.getRevisionId(),p.getVersion(),p.getState(),p.getVisibility(),p.getTitle(),String.join("\n",p.getContext(),p.getOutcome(),p.getRationale(),p.getAlternatives(),p.getStatus()),p.getCreatedAt(),p.getUpdatedAt(),e.getEventId(),e.getAggregateId(),e.getSpaceId(),e.getActorId(),e.getProducer(),e.getAggregateType(),e.getEventType(),e.getEventVersion(),e.getOccurredAtEpochMillis(),e.getTraceId());
 }
 private static BrainProjection.Input source(String type,String raw,String revision,long version,String state,String visibility,String title,String body,String created,String updated,String event,String aggregate,String space,String actor,String producer,String aggregateType,String eventType,int eventVersion,long occurred,String trace){
  BrainProjection.envelope(producer,aggregateType,"content-service",type,eventVersion,occurred,actor,trace);var id=BrainProjection.id(raw);BrainProjection.id(revision);
  if(!id.equals(BrainProjection.id(aggregate))||version<1||!Set.of("ACTIVE","RETIRED").contains(state)||!visibility.equals("PRIVATE")||!Set.of(type+"Created",type+"Updated",type+"Retired").contains(eventType)||eventType.equals(type+"Retired")!=state.equals("RETIRED")||eventType.equals(type+"Created")&&version!=1||title.isBlank()||title.codePointCount(0,title.length())>200)throw BrainProjection.invalid();
  BrainProjection.text(title,800);
  try{if(Instant.parse(created).isAfter(Instant.parse(updated))||Instant.parse(updated).toEpochMilli()!=occurred)throw BrainProjection.invalid();}catch(java.time.DateTimeException x){throw BrainProjection.invalid();}
  boolean visible=state.equals("ACTIVE");return new BrainProjection.Input(BrainProjection.id(event),id,BrainProjection.id(space),type.toUpperCase(Locale.ROOT),version,state,visibility,visible?title:"",visible?body:"",List.of(),trace);
 }
}
