package io.knowledgebrain.activity;
import java.time.*;import java.util.*;
import io.knowledgebrain.contracts.learning.roadmap.v1.RoadmapEventEnvelopeV1;
import io.knowledgebrain.contracts.planning.goal.v1.*;
import io.knowledgebrain.contracts.planning.task.v1.*;
import io.knowledgebrain.contracts.planning.schedule.v1.*;
/** Derived, immutable display facts. Planning remains the only canonical owner. */
final class PlanningActivity {
 private static IllegalArgumentException bad(){return ActivityEvent.invalid();}
 private static String text(String v,int max,boolean title){if(v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>max||title&&(v.isBlank()||v.codePointCount(0,v.length())>200)||v.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff||Character.isISOControl(c)&&!( !title&&(c=='\n'||c=='\r'||c=='\t'))))throw bad();return v;}
 private static String date(String v){if(v.isEmpty())return null;if(!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||LocalDate.parse(v).getYear()<1)throw bad();return v;}
 private static UUID optional(String v){return v.isEmpty()?null:ActivityEvent.id(v);}
 private static Instant utc(String v){if(!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"))throw bad();var t=OffsetDateTime.parse(v);if(t.getYear()<1||!t.toInstant().toString().equals(v))throw bad();return t.toInstant();}
 private static void local(String v,String offset,Instant utc){if(!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")||LocalDateTime.parse(v).getYear()<1||!ZoneOffset.of(offset).getId().equals(offset)||!LocalDateTime.parse(v).toInstant(ZoneOffset.of(offset)).equals(utc))throw bad();}
 static ActivityEvent decode(RoadmapEventEnvelopeV1 e,String kind)throws com.google.protobuf.InvalidProtocolBufferException {
  UUID aggregate=ActivityEvent.id(e.getAggregateId()),revision;long version;String title,description,state,status=null,day=null,created,updated,type;UUID goal=null,task=null;ActivityEvent.Planning info;
  switch(kind){
   case "GOAL"->{var p=GoalChangedPayloadV1.parseFrom(e.getPayload());if(!aggregate.equals(ActivityEvent.id(p.getGoalId()))||!p.getVisibility().equals("PRIVATE"))throw bad();revision=ActivityEvent.id(p.getRevisionId());version=p.getVersion();title=p.getTitle();description=p.getDescription();state=p.getState();status=p.getStatus();day=date(p.getTargetDate());created=p.getCreatedAt();updated=p.getUpdatedAt();type="Goal";info=new ActivityEvent.Planning(status,day,null,null,null,null,null,null,null,null,null);}
   case "TASK"->{var p=TaskChangedPayloadV1.parseFrom(e.getPayload());if(!aggregate.equals(ActivityEvent.id(p.getTaskId()))||!p.getVisibility().equals("PRIVATE"))throw bad();revision=ActivityEvent.id(p.getRevisionId());version=p.getVersion();title=p.getTitle();description=p.getDescription();state=p.getState();status=p.getStatus();day=date(p.getDueDate());goal=optional(p.getGoalId());created=p.getCreatedAt();updated=p.getUpdatedAt();type="Task";info=new ActivityEvent.Planning(status,day,goal,null,null,null,null,null,null,null,null);}
   case "SCHEDULE"->{var p=ScheduleChangedPayloadV1.parseFrom(e.getPayload());if(!aggregate.equals(ActivityEvent.id(p.getScheduleId()))||!p.getVisibility().equals("PRIVATE"))throw bad();revision=ActivityEvent.id(p.getRevisionId());version=p.getVersion();title=p.getTitle();description=p.getDescription();state=p.getState();created=p.getCreatedAt();updated=p.getUpdatedAt();type="Schedule";var start=utc(p.getStartsAt());var end=utc(p.getEndsAt());if(!end.isAfter(start)||!ZoneId.getAvailableZoneIds().contains(p.getTimeZone()))throw bad();local(p.getStartsLocal(),p.getStartsOffset(),start);local(p.getEndsLocal(),p.getEndsOffset(),end);task=optional(p.getTaskId());info=new ActivityEvent.Planning(null,null,null,task,p.getStartsAt(),p.getEndsAt(),p.getTimeZone(),p.getStartsLocal(),p.getEndsLocal(),p.getStartsOffset(),p.getEndsOffset());}
   default->throw bad();
  }
  if(version<1||!Set.of("ACTIVE","RETIRED").contains(state)||status!=null&&!Set.of("NOT_STARTED","IN_PROGRESS","COMPLETED").contains(status)||!e.getAggregateType().equals(type)||!Set.of(type+"Created",type+"Updated",type+"Retired").contains(e.getEventType())||e.getEventType().equals(type+"Created")&&version!=1||state.equals("RETIRED")!=e.getEventType().equals(type+"Retired")||Instant.parse(updated).toEpochMilli()!=e.getOccurredAtEpochMillis()||Instant.parse(created).isAfter(Instant.parse(updated)))throw bad();
  return new ActivityEvent(ActivityEvent.id(e.getEventId()),ActivityEvent.id(e.getSpaceId()),ActivityEvent.id(e.getActorId()),e.getTraceId(),Instant.ofEpochMilli(e.getOccurredAtEpochMillis()).toString(),kind,e.getEventType(),type,aggregate,version,null,null,text(title,800,true),"",state,text(description,32768,false),null,null,0,"/api/spaces/"+e.getSpaceId()+"/"+kind.toLowerCase(Locale.ROOT)+"s/"+aggregate,info,null,null,null,null,null,null);
 }
}
