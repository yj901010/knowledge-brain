package io.knowledgebrain.activity;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import io.knowledgebrain.contracts.learning.roadmap.v1.RoadmapEventEnvelopeV1;
import io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2;

/** Frozen Knowledge Claim facts. No current Concept lookup or verification inference. */
final class ClaimActivity {
 static boolean target(ActivityEvent e){var c=e.claim();return c!=null&&e.roadmapId()==null&&e.nodeId()==null&&e.planning()==null&&e.content()==null&&e.note()==null&&e.noteConcepts()==null&&e.metadata()==null&&e.noteLinks()==null&&e.noteAttachments()==null&&e.startedAt()==null&&e.endedAt()==null&&e.durationSeconds()==0&&e.targetPath().equals("/api/spaces/"+e.spaceId()+"/concepts/"+c.subjectConceptId()+"/claims/"+e.aggregateId());}
 static ActivityEvent decode(RoadmapEventEnvelopeV1 e,boolean legacy)throws com.google.protobuf.InvalidProtocolBufferException {
  var p=ClaimChangedPayloadV2.parseFrom(e.getPayload());var claim=ActivityEvent.id(p.getClaimId());var subject=ActivityEvent.id(p.getSubjectConceptId());var predicate=ActivityEvent.id(p.getPredicateConceptId());var revision=ActivityEvent.id(p.getRevisionId());
  if(!claim.equals(ActivityEvent.id(e.getAggregateId()))||!e.getAggregateType().equals("Claim")||!p.getVisibility().equals("PRIVATE")||!p.getVerificationState().equals("DRAFT")||p.getVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getState())||!Set.of("ClaimCreated","ClaimUpdated","ClaimRetired").contains(e.getEventType())||(p.getVersion()==1)!=e.getEventType().equals("ClaimCreated")||p.getState().equals("RETIRED")!=e.getEventType().equals("ClaimRetired"))throw ActivityEvent.invalid();
  ActivityEvent.ClaimObject object;
  if(p.getObjectType().equals("CONCEPT")){if(!p.getObjectValue().isEmpty())throw ActivityEvent.invalid();object=new ActivityEvent.ClaimObject("CONCEPT",ActivityEvent.id(p.getObjectConceptId()),null);}
  else{if(legacy||!p.getObjectConceptId().isEmpty())throw ActivityEvent.invalid();scalar(p.getObjectType(),p.getObjectValue());object=new ActivityEvent.ClaimObject(p.getObjectType(),null,p.getObjectValue());}
  return new ActivityEvent(ActivityEvent.id(e.getEventId()),ActivityEvent.id(e.getSpaceId()),ActivityEvent.id(e.getActorId()),e.getTraceId(),Instant.ofEpochMilli(e.getOccurredAtEpochMillis()).toString(),"CLAIM",e.getEventType(),"Claim",claim,p.getVersion(),null,null,"개념 관계","",p.getState(),"",null,null,0,"/api/spaces/"+e.getSpaceId()+"/concepts/"+subject+"/claims/"+claim,null,null,null,null,null,null,null,new ActivityEvent.Claim(revision,subject,predicate,object,p.getVerificationState()));
 }
 static void scalar(String type,String value){
  if(value==null||value.length()>4000||value.indexOf(0)>=0||value.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw ActivityEvent.invalid();
  try{String canonical=switch(type){
   case "STRING"->{if(value.isEmpty()||value.codePointCount(0,value.length())>1000)throw ActivityEvent.invalid();yield value.strip();}
   case "NUMBER"->{if(value.length()>128||!value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"))throw ActivityEvent.invalid();var n=new BigDecimal(value).stripTrailingZeros();if(n.precision()>38||n.scale()>18||n.precision()-n.scale()>38)throw ActivityEvent.invalid();yield n.signum()==0?"0":n.toPlainString();}
   case "DATE"->{if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||value.startsWith("0000"))throw ActivityEvent.invalid();yield LocalDate.parse(value).toString();}
   case "BOOLEAN"->{if(!Set.of("true","false").contains(value))throw ActivityEvent.invalid();yield value;}
   default->throw ActivityEvent.invalid();};if(!canonical.equals(value))throw ActivityEvent.invalid();
  }catch(RuntimeException error){throw ActivityEvent.invalid();}
 }
}
