package io.knowledgebrain.content;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import io.knowledgebrain.contracts.content.metadata.v1.*;
import com.google.protobuf.ByteString;
final class NoteMetadataStore {
    record Tag(UUID tagId,String name) {}
    record Property(UUID propertyId,String name,String type,JsonNode value) {}
    record Snapshot(UUID noteId,long metadataVersion,List<Tag> tags,List<Property> properties) {}
    record Revision(UUID revisionId,long metadataVersion,UUID actorId,Instant occurredAt,Snapshot snapshot) {}
    record Definition(UUID id,String name,String type,long activeNoteCount) {}
    record Page(List<Definition> items,UUID nextAfter) {}
    record PropertyNote(UUID noteId,String title,long version,long metadataVersion,Instant updatedAt,JsonNode value) {}
    record PropertyNotesPage(UUID propertyId,String name,String type,List<PropertyNote> items,UUID nextAfter,UUID tagId) {
        PropertyNotesPage(UUID propertyId,String name,String type,List<PropertyNote> items,UUID nextAfter){this(propertyId,name,type,items,nextAfter,null);}
    }
    private final JdbcTemplate jdbc;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    NoteMetadataStore(JdbcTemplate jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;ids=new UuidV7(clock);}
    PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit) {return propertyNotes(space,property,after,limit,null);}
    PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit,String value) {return propertyNotes(space,property,after,limit,value,null,null);}
    PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit,String value,String min,String max) {return propertyNotes(space,property,after,limit,value,min,max,null);}
    PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit,String value,String min,String max,UUID tag) {
        PropertyValueFilter.selection(value,min,max);
        var definitions=jdbc.query("SELECT name,value_type FROM property_definition WHERE space_id=? AND property_id=?",(rs,n)->new Definition(property,rs.getString(1),rs.getString(2),0),space,property);
        if(definitions.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Property unavailable");var definition=definitions.getFirst();
        if(tag!=null){NoteStore.id(tag);if(jdbc.queryForObject("SELECT count(*) FROM content_tag WHERE space_id=? AND tag_id=?",Long.class,space,tag)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tag unavailable");}
        var filter=value==null?null:PropertyValueFilter.parse(definition.type(),value);var bounds=PropertyValueFilter.bounds(definition.type(),min,max);
        String sql="SELECT n.note_id,n.title,n.version,n.metadata_version,n.updated_at,p.value::text FROM note_property p JOIN note n ON n.space_id=p.space_id AND n.note_id=p.note_id WHERE p.space_id=? AND p.property_id=? AND n.visibility='PRIVATE' AND n.state='ACTIVE'"+(filter==null?"":filter.contains()?" AND p.value @> ?::jsonb":" AND p.value = ?::jsonb")+(bounds.minJson()==null?"":" AND p.value >= ?::jsonb")+(bounds.maxJson()==null?"":" AND p.value <= ?::jsonb")+(tag==null?"":" AND EXISTS (SELECT 1 FROM note_tag t WHERE t.space_id=p.space_id AND t.note_id=p.note_id AND t.tag_id=?)")+(after==null?"":" AND p.note_id>?")+" ORDER BY p.note_id LIMIT ?";
        var args=new ArrayList<Object>();args.add(space);args.add(property);if(filter!=null)args.add(filter.json());if(bounds.minJson()!=null)args.add(bounds.minJson());if(bounds.maxJson()!=null)args.add(bounds.maxJson());if(tag!=null)args.add(tag);if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(sql,(rs,n)->new PropertyNote(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getTimestamp(5).toInstant(),json.readTree(rs.getString(6))),args.toArray());
        boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        return new PropertyNotesPage(property,definition.name(),definition.type(),page,more?page.getLast().noteId():null,tag);
    }
    Snapshot get(NoteStore.Note note) {
        long version=jdbc.queryForObject("SELECT metadata_version FROM note WHERE note_id=?",Long.class,note.noteId());
        var tags=jdbc.query("SELECT t.tag_id,t.name FROM note_tag x JOIN content_tag t ON t.space_id=x.space_id AND t.tag_id=x.tag_id WHERE x.space_id=? AND x.note_id=? ORDER BY t.name,t.tag_id",(rs,n)->new Tag(rs.getObject(1,UUID.class),rs.getString(2)),note.spaceId(),note.noteId());
        var properties=jdbc.query("SELECT d.property_id,d.name,d.value_type,p.value::text FROM note_property p JOIN property_definition d ON d.space_id=p.space_id AND d.property_id=p.property_id WHERE p.space_id=? AND p.note_id=? ORDER BY d.name,d.property_id",(rs,n)->new Property(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),json.readTree(rs.getString(4))),note.spaceId(),note.noteId());return new Snapshot(note.noteId(),version,List.copyOf(tags),List.copyOf(properties));
    }
    Snapshot replace(NoteStore.Note note,UUID actor,long expected,MetadataInput.Edit edit,String trace) {
        long current=jdbc.queryForObject("SELECT metadata_version FROM note WHERE note_id=?",Long.class,note.noteId());if(current!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Metadata changed; reload before saving");
        jdbc.update("DELETE FROM note_tag WHERE note_id=?",note.noteId());jdbc.update("DELETE FROM note_property WHERE note_id=?",note.noteId());
        for(var name:edit.tags()) {
            UUID candidate=ids.next();jdbc.update("INSERT INTO content_tag(tag_id,space_id,name,name_key) VALUES (?,?,?,?) ON CONFLICT(space_id,name_key) DO NOTHING",candidate,note.spaceId(),name,name);
            UUID tag=jdbc.queryForObject("SELECT tag_id FROM content_tag WHERE space_id=? AND name_key=?",UUID.class,note.spaceId(),name);jdbc.update("INSERT INTO note_tag(space_id,note_id,tag_id) VALUES (?,?,?)",note.spaceId(),note.noteId(),tag);
        }
        for(var field:edit.properties()) {
            UUID candidate=ids.next();jdbc.update("INSERT INTO property_definition(property_id,space_id,name,value_type) VALUES (?,?,?,?) ON CONFLICT(space_id,name) DO NOTHING",candidate,note.spaceId(),field.name(),field.type());
            var definition=jdbc.queryForMap("SELECT property_id,value_type FROM property_definition WHERE space_id=? AND name=?",note.spaceId(),field.name());if(!field.type().equals(definition.get("value_type")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Property type is fixed within its Space");
            jdbc.update("INSERT INTO note_property(space_id,note_id,property_id,value) VALUES (?,?,?,?::jsonb)",note.spaceId(),note.noteId(),definition.get("property_id"),field.json());
        }return publish(note,actor,trace);
    }
    Snapshot publish(NoteStore.Note note,UUID actor,String trace) {
        jdbc.update("UPDATE note SET metadata_version=metadata_version+1 WHERE note_id=?",note.noteId());var snapshot=get(note);UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();
        jdbc.update("INSERT INTO note_metadata_revision(revision_id,space_id,note_id,metadata_version,actor_id,occurred_at,snapshot) VALUES (?,?,?,?,?,?,?::jsonb)",revision,note.spaceId(),note.noteId(),snapshot.metadataVersion(),actor,Timestamp.from(now),json.writeValueAsString(snapshot));
        var payload=NoteMetadataChangedPayloadV1.newBuilder().setNoteId(note.noteId().toString()).setMetadataVersion(snapshot.metadataVersion()).setNoteVersion(note.version()).setNoteState(note.state()).setRevisionId(revision.toString());
        for(var tag:snapshot.tags())payload.addTags(MetadataTagV1.newBuilder().setTagId(tag.tagId().toString()).setName(tag.name()));
        for(var field:snapshot.properties())payload.addProperties(MetadataPropertyV1.newBuilder().setPropertyId(field.propertyId().toString()).setName(field.name()).setValueType(field.type()).setJsonValue(field.value().toString()));
        var envelope=MetadataEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("NoteMetadataChanged").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("NoteMetadata").setAggregateId(note.noteId().toString()).setSpaceId(note.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.build().toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'note-metadata',?,'NoteMetadataChanged',?,?,'content','note-metadata-events','1',?)",event,note.noteId(),Timestamp.from(now),trace,envelope.toByteArray());return snapshot;
    }
    List<Revision> history(UUID note,long before,int limit) {return jdbc.query("SELECT revision_id,metadata_version,actor_id,occurred_at,snapshot::text FROM note_metadata_revision WHERE note_id=? AND metadata_version<? ORDER BY metadata_version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),json.readValue(rs.getString(5),Snapshot.class)),note,before,limit);}
    Page definitions(UUID space,boolean tags,UUID after,int limit) {return definitions(space,tags,after,limit,null);}
    Page definitions(UUID space,boolean tags,UUID after,int limit,String query) {
        String q=query==null?null:MetadataNameQuery.parse(query);
        String id=tags?"tag_id":"property_id",table=tags?"content_tag":"property_definition",relation=tags?"note_tag":"note_property";
        String sql="SELECT d."+id+",d.name,"+(tags?"'TAG'":"d.value_type")+",(SELECT count(*) FROM "+relation+" x JOIN note n ON n.space_id=x.space_id AND n.note_id=x.note_id WHERE x.space_id=d.space_id AND x."+id+"=d."+id+" AND n.state='ACTIVE') FROM "+table+" d WHERE d.space_id=?"+(q==null?"":" AND position(? in d.name)>0")+(after==null?"":" AND d."+id+">?")+" ORDER BY d."+id+" LIMIT ?";
        var args=new ArrayList<Object>();args.add(space);if(q!=null)args.add(q);if(after!=null)args.add(after);args.add(limit+1);var rows=jdbc.query(sql,(rs,n)->new Definition(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),args.toArray());var page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));return new Page(page,rows.size()>limit?page.getLast().id():null);
    }
}
