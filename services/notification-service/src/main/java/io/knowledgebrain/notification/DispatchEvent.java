package io.knowledgebrain.notification;
import java.time.*;import java.time.format.DateTimeFormatter;import java.util.*;import java.nio.charset.StandardCharsets;
import io.knowledgebrain.contracts.planning.delivery.v1.*;
/** Consumer validation is independent of Planning's runtime and database. */
final class DispatchEvent {
 static final String TOPIC="planning.reminder-delivery.v1";
 record Source(UUID deliveryId,UUID reminderId,long reminderVersion,UUID scheduleId,long scheduleVersion,int minutesBefore,
               String title,String startsAt,String endsAt,String timeZone,String startsLocal,String endsLocal,String startsOffset,String endsOffset,
               String remindsAt,String remindsLocal,String remindsOffset,String armedAt,String dispatchedAt,UUID armedBy) {}
 record Event(ReminderDeliveryEnvelopeV1 envelope,ReminderDeliveryPayloadV1 payload,Source source) {}
 static IllegalArgumentException bad(){return new IllegalArgumentException("Invalid private dispatch event");}
 static UUID id(String raw){try{var v=UUID.fromString(raw);if(v.version()!=7||v.variant()!=2||!v.toString().equals(raw))throw bad();return v;}catch(RuntimeException e){throw bad();}}
 static Instant time(String raw){try{var v=Instant.parse(raw);if(!v.toString().equals(raw)||v.atOffset(ZoneOffset.UTC).getYear()<1||v.atOffset(ZoneOffset.UTC).getYear()>9999)throw bad();return v;}catch(RuntimeException e){throw bad();}}
 static void pin(String utc,String local,String offset){try{if(!utc.matches("(?!0000)[0-9]{4}-[0-9-]{5}T[0-9:]{8}Z"))throw bad();var fmt=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");var wall=LocalDateTime.parse(local);if(!fmt.format(wall).equals(local)||wall.getYear()<1||wall.getYear()>9999||!ZoneOffset.of(offset).toString().equals(offset)||!wall.toInstant(ZoneOffset.of(offset)).equals(time(utc)))throw bad();}catch(RuntimeException e){throw bad();}}
 static Event decode(byte[] bytes){
  try{if(bytes==null||bytes.length>65536)throw bad();var e=ReminderDeliveryEnvelopeV1.parseFrom(bytes);id(e.getEventId());id(e.getAggregateId());id(e.getSpaceId());id(e.getActorId());if(e.getEventVersion()!=1||!e.getProducer().equals("planning-service")||!e.getAggregateType().equals("ReminderDelivery")||!e.getTraceId().matches("[0-9a-f]{32}")||e.getTraceId().equals("0".repeat(32)))throw bad();
   var p=ReminderDeliveryPayloadV1.parseFrom(e.getPayload());id(p.getDeliveryId());id(p.getReminderId());id(p.getScheduleId());id(p.getArmedBy());
   var types=Map.of("PENDING","ReminderDeliveryArmed","CANCELLED","ReminderDeliveryCancelled","SUPPRESSED","ReminderDeliverySuppressed","DISPATCHED","ReminderDispatched");
   if(!e.getAggregateId().equals(p.getDeliveryId())||!e.getEventType().equals(types.get(p.getState()))||p.getReminderVersion()<1||p.getScheduleVersion()<1||p.getMinutesBefore()<0||p.getMinutesBefore()>10080||p.getVersion()!=(p.getState().equals("PENDING")?1:2))throw bad();
   String title=p.getTitle();if(title.isBlank()||title.codePointCount(0,title.length())>200||title.getBytes(StandardCharsets.UTF_8).length>800||title.codePoints().anyMatch(Character::isISOControl)||!ZoneId.getAvailableZoneIds().contains(p.getTimeZone()))throw bad();
   pin(p.getStartsAt(),p.getStartsLocal(),p.getStartsOffset());pin(p.getEndsAt(),p.getEndsLocal(),p.getEndsOffset());pin(p.getRemindsAt(),p.getRemindsLocal(),p.getRemindsOffset());
   if(!time(p.getEndsAt()).isAfter(time(p.getStartsAt()))||!time(p.getStartsAt()).minusSeconds(60L*p.getMinutesBefore()).equals(time(p.getRemindsAt()))||time(p.getUpdatedAt()).isBefore(time(p.getCreatedAt()))||e.getOccurredAtEpochMillis()!=time(p.getUpdatedAt()).toEpochMilli())throw bad();
   if(p.getState().equals("CANCELLED")?!p.getReason().equals("USER_CANCELLED"):p.getState().equals("SUPPRESSED")?!p.getReason().equals("SOURCE_CHANGED"):!p.getReason().isEmpty())throw bad();
   Source source=null;
   if(p.getState().equals("DISPATCHED")){if(time(p.getTriggeredAt()).isBefore(time(p.getRemindsAt()))||time(p.getUpdatedAt()).isBefore(time(p.getTriggeredAt()))||!e.getActorId().equals(p.getArmedBy()))throw bad();source=new Source(id(p.getDeliveryId()),id(p.getReminderId()),p.getReminderVersion(),id(p.getScheduleId()),p.getScheduleVersion(),p.getMinutesBefore(),p.getTitle(),p.getStartsAt(),p.getEndsAt(),p.getTimeZone(),p.getStartsLocal(),p.getEndsLocal(),p.getStartsOffset(),p.getEndsOffset(),p.getRemindsAt(),p.getRemindsLocal(),p.getRemindsOffset(),p.getCreatedAt(),p.getTriggeredAt(),id(p.getArmedBy()));}
   else if(!p.getTriggeredAt().isEmpty())throw bad();
   return new Event(e,p,source);
  }catch(Exception e){throw bad();}
 }
 static void transport(String topic,String key,Map<String,String> headers,byte[] bytes){var v=decode(bytes);var e=v.envelope();if(!TOPIC.equals(topic)||!e.getAggregateId().equals(key))throw bad();for(var h:Map.of("id",e.getEventId(),"trace_id",e.getTraceId(),"schema_group","planning","schema_artifact","reminder-delivery-events","schema_version","1").entrySet())if(!h.getValue().equals(headers.get(h.getKey())))throw bad();}
}
