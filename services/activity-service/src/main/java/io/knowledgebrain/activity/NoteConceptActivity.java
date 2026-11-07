package io.knowledgebrain.activity;
import java.time.Instant;import java.util.*;
import io.knowledgebrain.contracts.learning.roadmap.v1.RoadmapEventEnvelopeV1;
import io.knowledgebrain.contracts.content.concepts.v1.NoteConceptsChangedPayloadV1;
/** Original Content association facts. Titles and visibility are not present in this frozen payload. */
final class NoteConceptActivity {
 static boolean target(ActivityEvent e){return e.noteConcepts()!=null&&e.note()==null&&e.content()==null&&e.planning()==null&&e.roadmapId()==null&&e.nodeId()==null&&e.startedAt()==null&&e.endedAt()==null&&e.durationSeconds()==0&&e.targetPath().equals("/api/spaces/"+e.spaceId()+"/notes/"+e.aggregateId()+"/concepts");}
 static ActivityEvent decode(RoadmapEventEnvelopeV1 e)throws com.google.protobuf.InvalidProtocolBufferException {
  var p=NoteConceptsChangedPayloadV1.parseFrom(e.getPayload());var note=ActivityEvent.id(p.getNoteId());var revision=ActivityEvent.id(p.getRevisionId());
  if(!note.equals(ActivityEvent.id(e.getAggregateId()))||!e.getAggregateType().equals("NoteConceptLinks")||!e.getEventType().equals("NoteConceptsChanged")||p.getConceptVersion()<1||p.getNoteVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getNoteState())||p.getLinksCount()>20||p.getNoteState().equals("RETIRED")&&p.getLinksCount()!=0)throw ActivityEvent.invalid();
  var links=new ArrayList<ActivityEvent.Link>();var ids=new HashSet<UUID>();var concepts=new HashSet<UUID>();for(var link:p.getLinksList()){var id=ActivityEvent.id(link.getLinkId());var concept=ActivityEvent.id(link.getConceptId());if(!ids.add(id)||!concepts.add(concept))throw ActivityEvent.invalid();links.add(new ActivityEvent.Link(id,concept));}
  return new ActivityEvent(ActivityEvent.id(e.getEventId()),ActivityEvent.id(e.getSpaceId()),ActivityEvent.id(e.getActorId()),e.getTraceId(),Instant.ofEpochMilli(e.getOccurredAtEpochMillis()).toString(),"NOTE_CONCEPTS",e.getEventType(),e.getAggregateType(),note,p.getConceptVersion(),null,null,"노트 개념 연결","",p.getNoteState(),"",null,null,0,"/api/spaces/"+e.getSpaceId()+"/notes/"+note+"/concepts",null,null,null,new ActivityEvent.NoteConcepts(revision,p.getNoteVersion(),List.copyOf(links)),null,null,null);
 }
}
