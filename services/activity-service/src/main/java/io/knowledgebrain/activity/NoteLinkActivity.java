package io.knowledgebrain.activity;
import java.time.Instant;import java.util.*;
import io.knowledgebrain.contracts.learning.roadmap.v1.RoadmapEventEnvelopeV1;
import io.knowledgebrain.contracts.content.links.v1.NoteLinksChangedPayloadV1;
/** Original outgoing occurrences, including unresolved references. Never hydrate historical target titles. */
final class NoteLinkActivity {
 private static IllegalArgumentException bad(){return ActivityEvent.invalid();}
 private static String text(String v,boolean target){if(v.codePointCount(0,v.length())>200||v.indexOf(0)>=0||v.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff)||v.indexOf('[')>=0||v.indexOf(']')>=0||v.indexOf('\r')>=0||v.indexOf('\n')>=0||v.indexOf('|')>=0||!v.strip().equals(v)||target&&(v.isEmpty()||v.indexOf('#')>=0))throw bad();return v;}
 static boolean target(ActivityEvent e){return e.noteLinks()!=null&&e.metadata()==null&&e.noteConcepts()==null&&e.note()==null&&e.content()==null&&e.planning()==null&&e.roadmapId()==null&&e.nodeId()==null&&e.startedAt()==null&&e.endedAt()==null&&e.durationSeconds()==0&&e.targetPath().equals("/api/spaces/"+e.spaceId()+"/notes/"+e.aggregateId()+"/links");}
 static ActivityEvent decode(RoadmapEventEnvelopeV1 e)throws com.google.protobuf.InvalidProtocolBufferException{
  var p=NoteLinksChangedPayloadV1.parseFrom(e.getPayload());var note=ActivityEvent.id(p.getSourceNoteId());var revision=ActivityEvent.id(p.getRevisionId());if(!note.equals(ActivityEvent.id(e.getAggregateId()))||!e.getAggregateType().equals("NoteLinks")||!e.getEventType().equals("NoteLinksChanged")||p.getNoteVersion()<1||p.getLinkVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getSourceState())||p.getLinksCount()>256||p.getSourceState().equals("RETIRED")&&p.getLinksCount()!=0)throw bad();
  var links=new ArrayList<ActivityEvent.WikiLink>();var ids=new HashSet<UUID>();int end=0;for(var v:p.getLinksList()){
   var id=ActivityEvent.id(v.getLinkId());String token=text(v.getTargetToken(),true),label=text(v.getLabel(),false),fragment=text(v.getFragment(),false);var requested=v.getRequestedTargetId().isEmpty()?null:ActivityEvent.id(v.getRequestedTargetId());var target=v.getTargetNoteId().isEmpty()?null:ActivityEvent.id(v.getTargetNoteId());boolean bound=Set.of("RESOLVED","TARGET_RETIRED").contains(v.getStatus());
   if(!ids.add(id)||v.getOrdinal()!=links.size()||v.getStartOffset()<end||v.getStartOffset()>32768||v.getEndOffset()>32768||v.getEndOffset()<v.getStartOffset()+token.length()+4||!Set.of("RESOLVED","TARGET_RETIRED","UNRESOLVED","AMBIGUOUS").contains(v.getStatus())||bound!=(target!=null)||token.startsWith("note:")!=(requested!=null)||requested!=null&&(!token.equals("note:"+requested)||v.getStatus().equals("AMBIGUOUS")||target!=null&&!target.equals(requested)))throw bad();
   end=v.getEndOffset();links.add(new ActivityEvent.WikiLink(id,v.getOrdinal(),v.getStartOffset(),end,token,requested,label,fragment,target,v.getStatus()));
  }
  return new ActivityEvent(ActivityEvent.id(e.getEventId()),ActivityEvent.id(e.getSpaceId()),ActivityEvent.id(e.getActorId()),e.getTraceId(),Instant.ofEpochMilli(e.getOccurredAtEpochMillis()).toString(),"NOTE_LINKS",e.getEventType(),e.getAggregateType(),note,p.getLinkVersion(),null,null,"노트 Wiki Link","",p.getSourceState(),"",null,null,0,"/api/spaces/"+e.getSpaceId()+"/notes/"+note+"/links",null,null,null,null,null,new ActivityEvent.NoteLinks(revision,p.getNoteVersion(),List.copyOf(links)),null);
 }
}
