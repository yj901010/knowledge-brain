package io.knowledgebrain.activity;
import java.time.*;import java.util.*;import java.nio.charset.StandardCharsets;
import io.knowledgebrain.contracts.learning.roadmap.v1.RoadmapEventEnvelopeV1;
import io.knowledgebrain.contracts.content.v1.NoteChangedPayloadV1;
import io.knowledgebrain.contracts.content.daily.v1.DailyNoteChangedPayloadV1;
/** Bounded immutable event-time Note display facts; Content remains canonical. */
final class NoteActivity {
 private static IllegalArgumentException bad(){return ActivityEvent.invalid();}
 // The frozen Note contract permits controls other than NUL. Do not tighten it here.
 private static String text(String s,int bytes,boolean title){if(s.indexOf(0)>=0||s.getBytes(StandardCharsets.UTF_8).length>bytes||s.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff)||title&&(s.isBlank()||s.codePointCount(0,s.length())>200))throw bad();return s;}
 static String excerpt(String s){int end=0,bytes=0;while(end<s.length()){int cp=s.codePointAt(end);int n=cp<128?1:cp<2048?2:cp<65536?3:4;if(bytes+n>8192)break;bytes+=n;end+=Character.charCount(cp);}return s.substring(0,end);}
 static boolean target(ActivityEvent e){var n=e.note();return n!=null&&e.content()==null&&e.planning()==null&&e.roadmapId()==null&&e.nodeId()==null&&e.startedAt()==null&&e.endedAt()==null&&e.durationSeconds()==0&&e.targetPath().equals("/api/spaces/"+e.spaceId()+"/notes/"+e.aggregateId())&&(e.kind().equals("NOTE")?n.revisionId()!=null&&n.journalDate()==null&&n.timeZone()==null:n.revisionId()==null&&n.journalDate()!=null&&n.timeZone()!=null);}
 static ActivityEvent decode(RoadmapEventEnvelopeV1 e,String kind)throws com.google.protobuf.InvalidProtocolBufferException {
  UUID aggregate=ActivityEvent.id(e.getAggregateId()),revision=null;long version;String title,state,detail="",date=null,zone=null;int bytes=0;boolean truncated=false;
  if(kind.equals("NOTE")){var p=NoteChangedPayloadV1.parseFrom(e.getPayload());if(!aggregate.equals(ActivityEvent.id(p.getNoteId()))||!p.getVisibility().equals("PRIVATE")||!e.getAggregateType().equals("Note")||!Set.of("NoteCreated","NoteUpdated","NoteRetired").contains(e.getEventType()))throw bad();revision=ActivityEvent.id(p.getRevisionId());version=p.getVersion();state=p.getState();title=text(p.getTitle(),800,true);String markdown=text(p.getMarkdown(),32768,false);bytes=markdown.getBytes(StandardCharsets.UTF_8).length;detail=excerpt(markdown);truncated=bytes>detail.getBytes(StandardCharsets.UTF_8).length;if((version==1)!=e.getEventType().equals("NoteCreated")||state.equals("RETIRED")!=e.getEventType().equals("NoteRetired"))throw bad();}
  else if(kind.equals("DAILY_NOTE")){var p=DailyNoteChangedPayloadV1.parseFrom(e.getPayload());if(!aggregate.equals(ActivityEvent.id(p.getNoteId()))||!p.getVisibility().equals("PRIVATE")||!e.getAggregateType().equals("DailyNote")||!e.getEventType().equals("DailyNoteChanged"))throw bad();version=p.getNoteVersion();state=p.getNoteState();title=text(p.getTitle(),800,true);date=p.getJournalDate();zone=p.getTimezone();if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||LocalDate.parse(date).getYear()<1||zone.length()>100||!ZoneId.getAvailableZoneIds().contains(zone))throw bad();}
  else throw bad();
  if(version<1||!Set.of("ACTIVE","RETIRED").contains(state))throw bad();
  return new ActivityEvent(ActivityEvent.id(e.getEventId()),ActivityEvent.id(e.getSpaceId()),ActivityEvent.id(e.getActorId()),e.getTraceId(),Instant.ofEpochMilli(e.getOccurredAtEpochMillis()).toString(),kind,e.getEventType(),e.getAggregateType(),aggregate,version,null,null,title,"",state,detail,null,null,0,"/api/spaces/"+e.getSpaceId()+"/notes/"+aggregate,null,null,new ActivityEvent.Note(revision,date,zone,bytes,truncated),null,null,null,null);
 }
}
