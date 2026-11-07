package io.knowledgebrain.activity;
import java.time.*;import java.util.*;import java.text.Normalizer;import java.math.BigDecimal;
import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.DeserializationFeature;import tools.jackson.core.StreamReadFeature;
import io.knowledgebrain.contracts.learning.roadmap.v1.RoadmapEventEnvelopeV1;
import io.knowledgebrain.contracts.content.metadata.v1.NoteMetadataChangedPayloadV1;
/** Exact immutable metadata values. NUMBER never crosses a binary floating-point representation. */
final class MetadataActivity {
 private static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 private static IllegalArgumentException bad(){return ActivityEvent.invalid();}
 private static String name(String v){if(v.isEmpty()||v.codePointCount(0,v.length())>64||!Normalizer.normalize(v.strip(),Normalizer.Form.NFC).equals(v)||v.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff))throw bad();return v;}
 private static boolean text(String v,int max){return v.codePointCount(0,v.length())<=max&&v.indexOf(0)<0&&v.codePoints().noneMatch(c->c>=0xd800&&c<=0xdfff);}
 static void value(String type,String raw){if(raw.length()>100000)throw bad();try{var v=JSON.readTree(raw);if(v==null)throw bad();switch(type){
  case "STRING"->{if(!v.isString()||!text(v.asString(),1000))throw bad();}
  case "NUMBER"->{if(!v.isNumber())throw bad();var d=new BigDecimal(raw);var n=d.stripTrailingZeros();/* Frozen input allows38 digits and negative scale18, then expands to plain JSON in PostgreSQL. */if(n.precision()>38||n.scale()>18||(long)n.precision()-n.scale()>56)throw bad();}
  case "BOOLEAN"->{if(!v.isBoolean())throw bad();}
  case "DATE"->{if(!v.isString()||!v.asString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw bad();LocalDate.parse(v.asString());}
  case "STRING_LIST"->{if(!v.isArray()||v.size()>20)throw bad();for(var item:v)if(!item.isString()||!text(item.asString(),200))throw bad();}
  default->throw bad();}
 }catch(RuntimeException ex){throw bad();}}
 static boolean target(ActivityEvent e){return e.metadata()!=null&&e.noteConcepts()==null&&e.note()==null&&e.content()==null&&e.planning()==null&&e.roadmapId()==null&&e.nodeId()==null&&e.startedAt()==null&&e.endedAt()==null&&e.durationSeconds()==0&&e.targetPath().equals("/api/spaces/"+e.spaceId()+"/notes/"+e.aggregateId()+"/metadata");}
 static ActivityEvent decode(RoadmapEventEnvelopeV1 e)throws com.google.protobuf.InvalidProtocolBufferException {
  var p=NoteMetadataChangedPayloadV1.parseFrom(e.getPayload());var note=ActivityEvent.id(p.getNoteId());var revision=ActivityEvent.id(p.getRevisionId());if(!note.equals(ActivityEvent.id(e.getAggregateId()))||!e.getAggregateType().equals("NoteMetadata")||!e.getEventType().equals("NoteMetadataChanged")||p.getMetadataVersion()<1||p.getNoteVersion()<1||!Set.of("ACTIVE","RETIRED").contains(p.getNoteState())||p.getTagsCount()>32||p.getPropertiesCount()>32)throw bad();
  var tags=new ArrayList<ActivityEvent.Tag>();var properties=new ArrayList<ActivityEvent.Property>();var ids=new HashSet<UUID>();var names=new HashSet<String>();for(var t:p.getTagsList()){var id=ActivityEvent.id(t.getTagId());var name=name(t.getName());if(!ids.add(id)||!names.add(name))throw bad();tags.add(new ActivityEvent.Tag(id,name));}ids.clear();names.clear();for(var f:p.getPropertiesList()){var id=ActivityEvent.id(f.getPropertyId());var name=name(f.getName());if(!ids.add(id)||!names.add(name))throw bad();value(f.getValueType(),f.getJsonValue());properties.add(new ActivityEvent.Property(id,name,f.getValueType(),f.getJsonValue()));}
  return new ActivityEvent(ActivityEvent.id(e.getEventId()),ActivityEvent.id(e.getSpaceId()),ActivityEvent.id(e.getActorId()),e.getTraceId(),Instant.ofEpochMilli(e.getOccurredAtEpochMillis()).toString(),"NOTE_METADATA",e.getEventType(),e.getAggregateType(),note,p.getMetadataVersion(),null,null,"노트 태그·속성","",p.getNoteState(),"",null,null,0,"/api/spaces/"+e.getSpaceId()+"/notes/"+note+"/metadata",null,null,null,null,new ActivityEvent.Metadata(revision,p.getNoteVersion(),List.copyOf(tags),List.copyOf(properties)),null,null);
 }
}
