package io.knowledgebrain.content;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.content.v1.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class NoteStore {
    record Note(UUID noteId,UUID spaceId,String visibility,String state,long version,String title,String markdown,Instant createdAt,Instant updatedAt) {}
    record Revision(UUID revisionId,UUID noteId,long version,String state,String title,String markdown,UUID actorId,Instant occurredAt) {}
    record Summary(UUID noteId,String title,long version,Instant updatedAt) {}
    record Page(List<Summary> items,UUID nextAfter) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;
    private final NoteLinkStore links;
    private final NoteMetadataStore metadata;
    private final DailyNoteStore daily;
    private final NoteAttachmentStore attachments;
    private final NoteConceptStore concepts;
    NoteStore(DataSource source,Clock clock) {
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);links=new NoteLinkStore(jdbc,clock);metadata=new NoteMetadataStore(jdbc,clock);daily=new DailyNoteStore(jdbc,clock);attachments=new NoteAttachmentStore(jdbc,clock);concepts=new NoteConceptStore(jdbc,clock);bootstrapLinks();
    }
    static void id(UUID value) { if(value==null || value.version()!=7 || value.variant()!=2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UUIDv7 required"); }
    static void text(String title,String markdown) {
        if(title==null || title.isBlank() || title.codePointCount(0,title.length())>200 || title.indexOf(0)>=0 || markdown==null || markdown.indexOf(0)>=0 || markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Title or Markdown exceeds limits");
    }
    Note create(UUID spaceId,UUID actor,String title,String markdown,String trace) {
        id(spaceId);id(actor);text(title,markdown);validateTrace(trace);UUID noteId=ids.next();Instant now=clock.instant();
        return transaction.execute(status->{
            links.lock(spaceId);refreshDirty(spaceId);
            jdbc.update("INSERT INTO note(note_id,space_id,state,version,title,markdown,created_at,updated_at) VALUES (?,?,'ACTIVE',1,?,?,?,?)",noteId,spaceId,title,markdown,Timestamp.from(now),Timestamp.from(now));
            Note note=load(spaceId,noteId,false);updateLinks(note,null,actor,trace);revisionAndEvent(note,actor,trace,"NoteCreated");return note;
        });
    }
    Note change(UUID spaceId,UUID noteId,UUID actor,long expected,String title,String markdown,boolean retire,String trace) {
        id(spaceId);id(noteId);id(actor);validateTrace(trace);if(!retire)text(title,markdown);
        if(expected<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Positive version required");
        return transaction.execute(status->{
            links.lock(spaceId);refreshDirty(spaceId);Note prior=load(spaceId,noteId,true);
            if(prior==null || !"ACTIVE".equals(prior.state()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Note unavailable");
            if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Note changed; reload before saving");
            jdbc.update("UPDATE note SET title=?,markdown=?,state=?,version=version+1,updated_at=? WHERE note_id=?",retire?prior.title():title,retire?prior.markdown():markdown,retire?"RETIRED":"ACTIVE",Timestamp.from(clock.instant()),noteId);
            Note note=load(spaceId,noteId,false);updateLinks(note,WikiLinks.key(prior.title()),actor,trace);revisionAndEvent(note,actor,trace,retire?"NoteRetired":"NoteUpdated");if(retire){metadata.publish(note,actor,trace);attachments.retire(note,actor,trace);concepts.retire(note,actor,trace);}daily.publishIfDaily(note,actor,trace);return note;
        });
    }
    Note get(UUID spaceId,UUID noteId) {
        var note=load(spaceId,noteId,false);
        if(note==null || !"ACTIVE".equals(note.state()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Note unavailable");return note;
    }
    private Note load(UUID spaceId,UUID noteId,boolean lock) {
        var rows=jdbc.query("SELECT * FROM note WHERE space_id=? AND note_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Note(rs.getObject("note_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getString("visibility"),rs.getString("state"),rs.getLong("version"),rs.getString("title"),rs.getString("markdown"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),spaceId,noteId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    Page list(UUID spaceId,UUID after,int limit) { return list(spaceId,after,limit,false); }
    Page retired(UUID spaceId,UUID after,int limit) { return list(spaceId,after,limit,true); }
    private Page list(UUID spaceId,UUID after,int limit,boolean retired) {
        String sql="SELECT note_id,title,version,updated_at FROM note WHERE space_id=? AND visibility='PRIVATE' AND state='"+(retired?"RETIRED":"ACTIVE")+"'"+(after==null?"":" AND note_id>?")+" ORDER BY note_id LIMIT ?";
        Object[] args=after==null?new Object[]{spaceId,limit+1}:new Object[]{spaceId,after,limit+1};
        var rows=jdbc.query(sql,(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant()),args);
        boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(page,more?page.getLast().noteId():null);
    }
    Page tagNotes(UUID spaceId,UUID tagId,UUID after,int limit) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM content_tag WHERE space_id=? AND tag_id=?)",Boolean.class,spaceId,tagId)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Tag unavailable");
        String sql="SELECT n.note_id,n.title,n.version,n.updated_at FROM note_tag x JOIN note n ON n.space_id=x.space_id AND n.note_id=x.note_id WHERE x.space_id=? AND x.tag_id=? AND n.visibility='PRIVATE' AND n.state='ACTIVE'"+(after==null?"":" AND x.note_id>?")+" ORDER BY x.note_id LIMIT ?";
        Object[] args=after==null?new Object[]{spaceId,tagId,limit+1}:new Object[]{spaceId,tagId,after,limit+1};
        var rows=jdbc.query(sql,(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant()),args);
        boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(page,more?page.getLast().noteId():null);
    }
    List<Revision> history(UUID spaceId,UUID noteId,long before,int limit) {
        if(load(spaceId,noteId,false)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Note unavailable");
        return jdbc.query("SELECT revision_id,note_id,version,state,title,markdown,actor_id,occurred_at FROM note_revision WHERE note_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,UUID.class),rs.getTimestamp(8).toInstant()),noteId,before,limit);
    }
    private void revisionAndEvent(Note note,UUID actor,String trace,String type) {
        UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();
        jdbc.update("INSERT INTO note_revision(revision_id,note_id,version,title,markdown,state,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)",revision,note.noteId(),note.version(),note.title(),note.markdown(),note.state(),actor,Timestamp.from(now));
        var payload=NoteChangedPayloadV1.newBuilder().setNoteId(note.noteId().toString()).setRevisionId(revision.toString()).setVersion(note.version()).setState(note.state()).setVisibility(note.visibility()).setTitle(note.title()).setMarkdown(note.markdown()).build();
        var envelope=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("Note").setAggregateId(note.noteId().toString()).setSpaceId(note.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'note',?,?,?,?,'content','note-events','1',?)",event,note.noteId(),type,Timestamp.from(now),trace,envelope.toByteArray());
    }
    private void updateLinks(Note note,String oldKey,UUID actor,String trace) {
        jdbc.update("UPDATE note SET title_key=? WHERE note_id=?",WikiLinks.key(note.title()),note.noteId());
        links.rewrite(note,actor,trace);links.reconcile(note,oldKey,actor,trace,id->load(note.spaceId(),id,false));
    }
    private void bootstrapLinks() {
        var spaces=jdbc.queryForList("SELECT DISTINCT space_id FROM note WHERE links_dirty",UUID.class);
        for(var space:spaces)transaction.executeWithoutResult(status->{links.lock(space);refreshDirty(space);});
    }
    private void refreshDirty(UUID space) {
        var pending=jdbc.queryForList("SELECT note_id FROM note WHERE space_id=? AND links_dirty ORDER BY note_id",UUID.class,space);
        var oldKeys=new HashMap<UUID,String>();
        for(var id:pending) {var note=load(space,id,true);oldKeys.put(id,jdbc.queryForObject("SELECT title_key FROM note WHERE note_id=?",String.class,id));jdbc.update("UPDATE note SET title_key=? WHERE note_id=?",WikiLinks.key(note.title()),id);}
        for(var id:pending) {var note=load(space,id,false);UUID actor=jdbc.queryForObject("SELECT actor_id FROM note_revision WHERE note_id=? ORDER BY version DESC LIMIT 1",UUID.class,id);String trace=HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(16).putLong(ids.next().getMostSignificantBits()).putLong(ids.next().getLeastSignificantBits()).array());updateLinks(note,oldKeys.get(id),actor,trace);}
    }
    NoteLinkStore.Page outgoing(UUID spaceId,UUID noteId,Integer after,int limit) {
        return transaction.execute(status->{links.lock(spaceId);refreshDirty(spaceId);return links.outgoing(get(spaceId,noteId),after,limit);});
    }
    NoteLinkStore.BacklinkPage backlinks(UUID spaceId,UUID noteId,UUID after,int limit) {
        return transaction.execute(status->{links.lock(spaceId);refreshDirty(spaceId);return links.backlinks(get(spaceId,noteId),after,limit);});
    }
    NoteMetadataStore.Snapshot metadata(UUID space,UUID note) {return transaction.execute(status->{links.lock(space);return metadata.get(get(space,note));});}
    NoteMetadataStore.Snapshot metadata(UUID space,UUID note,UUID actor,long expected,MetadataInput.Edit edit,String trace) {
        id(space);id(note);id(actor);validateTrace(trace);return transaction.execute(status->{links.lock(space);return metadata.replace(get(space,note),actor,expected,edit,trace);});
    }
    List<NoteMetadataStore.Revision> metadataHistory(UUID space,UUID note,long before,int limit) {
        if(load(space,note,false)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Note unavailable");return metadata.history(note,before,limit);
    }
    NoteMetadataStore.PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit,String value,String min,String max,UUID tag) {return metadata.propertyNotes(space,property,after,limit,value,min,max,tag);}
    NoteMetadataStore.PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit,String value,String min,String max) {return metadata.propertyNotes(space,property,after,limit,value,min,max);}
    NoteMetadataStore.PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit,String value) {return metadata.propertyNotes(space,property,after,limit,value);}
    NoteMetadataStore.PropertyNotesPage propertyNotes(UUID space,UUID property,UUID after,int limit) {return metadata.propertyNotes(space,property,after,limit);}
    NoteMetadataStore.Page definitions(UUID space,boolean tags,UUID after,int limit,String q) {return metadata.definitions(space,tags,after,limit,q);}
    NoteMetadataStore.Page definitions(UUID space,boolean tags,UUID after,int limit) {return metadata.definitions(space,tags,after,limit);}
    DailyNoteStore.Opened openDaily(UUID space,String dateValue,String zoneValue,UUID actor,String trace){
        id(space);id(actor);validateTrace(trace);var date=DailyDates.date(dateValue);String zone=DailyDates.zone(zoneValue).getId();
        return transaction.execute(status->{
            links.lock(space);var binding=daily.binding(space,date);
            if(binding!=null){if(!binding.timezone().equals(zone))throw new ResponseStatusException(HttpStatus.CONFLICT,"Daily date already uses a different timezone");var note=load(space,binding.noteId(),false);if(!"ACTIVE".equals(note.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Daily note is retired; its binding and history are retained");return new DailyNoteStore.Opened(false,new DailyNoteStore.Entry(date,zone,note));}
            var note=create(space,actor,date.toString(),"# "+date+"\n",trace);daily.bind(space,date,zone,note.noteId());daily.publishIfDaily(note,actor,trace);return new DailyNoteStore.Opened(true,new DailyNoteStore.Entry(date,zone,note));
        });
    }
    DailyNoteStore.Entry daily(UUID space,String dateValue){
        id(space);var date=DailyDates.date(dateValue);return transaction.execute(status->{links.lock(space);var binding=daily.binding(space,date);if(binding==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Daily note unavailable");return new DailyNoteStore.Entry(date,binding.timezone(),get(space,binding.noteId()));});
    }
    DailyNoteStore.Today dailyToday(UUID space,String zoneValue){
        id(space);String zone=DailyDates.zone(zoneValue).getId();var date=DailyDates.today(clock,zone);return transaction.execute(status->{links.lock(space);var binding=daily.binding(space,date);var note=binding==null?null:load(space,binding.noteId(),false);var entry=note==null || !"ACTIVE".equals(note.state())?null:new DailyNoteStore.Entry(date,binding.timezone(),note);return new DailyNoteStore.Today(date,zone,entry);});
    }
    DailyNoteStore.Page dailyList(UUID space,String fromValue,String toValue,String beforeValue,int limit){
        id(space);if(limit<1 || limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid journal page limit");var from=fromValue==null?null:DailyDates.date(fromValue);var to=toValue==null?null:DailyDates.date(toValue);var before=beforeValue==null?null:DailyDates.date(beforeValue);if(from!=null && to!=null && from.isAfter(to))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid journal range");return daily.list(space,from,to,before,limit);
    }
    NoteAttachmentStore.Snapshot attachments(UUID space,UUID note){return transaction.execute(status->{links.lock(space);return attachments.get(get(space,note));});}
    NoteAttachmentStore.Snapshot attachments(UUID space,UUID note,UUID actor,long expected,List<MediaAvailabilityClient.File> files,String trace){id(space);id(note);id(actor);validateTrace(trace);return transaction.execute(status->{links.lock(space);return attachments.replace(get(space,note),actor,expected,files,trace);});}
    List<NoteAttachmentStore.Revision> attachmentHistory(UUID space,UUID note,long before,int limit){if(load(space,note,false)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Note unavailable");return attachments.history(note,before,limit);}
    NoteConceptStore.Snapshot concepts(UUID space,UUID note){return transaction.execute(status->{links.lock(space);return concepts.get(get(space,note));});}
    NoteConceptStore.NotesPage notesByConcept(UUID space,UUID concept,UUID after,int limit){id(space);id(concept);if(after!=null)id(after);if(limit<1 || limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid linked Note page");return concepts.notes(space,concept,after,limit);}
    NoteConceptStore.Snapshot concepts(UUID space,UUID note,UUID actor,long expected,List<UUID> ids,String trace){id(space);id(note);id(actor);validateTrace(trace);return transaction.execute(status->{links.lock(space);return concepts.replace(get(space,note),actor,expected,ids,trace);});}
    List<NoteConceptStore.Revision> conceptHistory(UUID space,UUID note,long before,int limit){if(load(space,note,false)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Note unavailable");return concepts.history(note,before,limit);}
    private static void validateTrace(String trace) { if(trace==null || !trace.matches("[0-9a-f]{32}") || trace.equals("0".repeat(32)))throw new IllegalArgumentException("Valid trace required"); }
}
