package io.knowledgebrain.content;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.content.concepts.v1.*;
import com.google.protobuf.ByteString;
final class NoteConceptStore {
 record ConceptLink(UUID linkId,UUID conceptId){}
 record Snapshot(UUID noteId,long conceptVersion,List<ConceptLink> items){}
 record Revision(UUID revisionId,long conceptVersion,UUID actorId,Instant occurredAt,Snapshot snapshot){}
 record LinkedNote(UUID noteId,String title){}
 record NotesPage(List<LinkedNote> items,UUID nextAfter){}
 private final JdbcTemplate jdbc;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=new JsonMapper();
 NoteConceptStore(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;ids=new UuidV7(clock);}
 NotesPage notes(UUID space,UUID concept,UUID after,int limit){
  String sql="SELECT n.note_id,n.title FROM note_concept_link l JOIN note n ON n.space_id=l.space_id AND n.note_id=l.note_id WHERE l.space_id=? AND l.concept_id=? AND n.visibility='PRIVATE' AND n.state='ACTIVE'"+(after==null?"":" AND n.note_id>?")+" ORDER BY n.note_id LIMIT ?";
  Object[] args=after==null?new Object[]{space,concept,limit+1}:new Object[]{space,concept,after,limit+1};
  var rows=jdbc.query(sql,(rs,n)->new LinkedNote(rs.getObject(1,UUID.class),rs.getString(2)),args);
  boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
  return new NotesPage(page,more?page.getLast().noteId():null);
 }
 Snapshot get(NoteStore.Note note){long version=jdbc.queryForObject("SELECT concept_link_version FROM note WHERE note_id=?",Long.class,note.noteId());var items=jdbc.query("SELECT concept_link_id,concept_id FROM note_concept_link WHERE space_id=? AND note_id=? ORDER BY ordinal",(rs,n)->new ConceptLink(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),note.spaceId(),note.noteId());return new Snapshot(note.noteId(),version,List.copyOf(items));}
 Snapshot replace(NoteStore.Note note,UUID actor,long expected,List<UUID> files,String trace){var prior=get(note);if(prior.conceptVersion()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"ConceptLinks changed; reload before saving");if(files.size()>20 || files.stream().distinct().count()!=files.size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid concept_links");var stable=new HashMap<UUID,UUID>();prior.items().forEach(x->stable.put(x.conceptId(),x.linkId()));jdbc.update("DELETE FROM note_concept_link WHERE note_id=?",note.noteId());int ordinal=0;for(var file:files){NoteStore.id(file);UUID id=stable.containsKey(file)?stable.get(file):ids.next();jdbc.update("INSERT INTO note_concept_link(concept_link_id,space_id,note_id,concept_id,ordinal) VALUES (?,?,?,?,?)",id,note.spaceId(),note.noteId(),file,ordinal++);}return publish(note,actor,trace);}
 void retire(NoteStore.Note note,UUID actor,String trace){if(get(note).conceptVersion()==0)return;jdbc.update("DELETE FROM note_concept_link WHERE note_id=?",note.noteId());publish(note,actor,trace);}
 private Snapshot publish(NoteStore.Note note,UUID actor,String trace){jdbc.update("UPDATE note SET concept_link_version=concept_link_version+1 WHERE note_id=?",note.noteId());var value=get(note);UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();jdbc.update("INSERT INTO note_concept_link_revision(revision_id,space_id,note_id,concept_link_version,actor_id,occurred_at,snapshot) VALUES (?,?,?,?,?,?,?::jsonb)",revision,note.spaceId(),note.noteId(),value.conceptVersion(),actor,Timestamp.from(now),json.writeValueAsString(value));var payload=NoteConceptsChangedPayloadV1.newBuilder().setNoteId(note.noteId().toString()).setConceptVersion(value.conceptVersion()).setNoteVersion(note.version()).setNoteState(note.state()).setRevisionId(revision.toString());for(var item:value.items())payload.addLinks(NoteConceptLinkV1.newBuilder().setLinkId(item.linkId().toString()).setConceptId(item.conceptId().toString()));var envelope=ConceptEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("NoteConceptsChanged").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("NoteConceptLinks").setAggregateId(note.noteId().toString()).setSpaceId(note.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.build().toByteArray())).build();jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'note-concepts',?,'NoteConceptsChanged',?,?,'content','note-concepts-events','1',?)",event,note.noteId(),Timestamp.from(now),trace,envelope.toByteArray());return value;}
 List<Revision> history(UUID note,long before,int limit){return jdbc.query("SELECT revision_id,concept_link_version,actor_id,occurred_at,snapshot::text FROM note_concept_link_revision WHERE note_id=? AND concept_link_version<? ORDER BY concept_link_version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant(),json.readValue(rs.getString(5),Snapshot.class)),note,before,limit);}
}
