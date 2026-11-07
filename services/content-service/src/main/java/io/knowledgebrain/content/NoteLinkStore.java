package io.knowledgebrain.content;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.content.links.v1.*;

final class NoteLinkStore {
    record Link(UUID linkId,int ordinal,int startOffset,int endOffset,String targetToken,UUID requestedTargetId,String label,String fragment,UUID targetNoteId,String status) {}
    record View(UUID linkId,int ordinal,int startOffset,int endOffset,String targetToken,String label,String fragment,UUID targetNoteId,String targetTitle,String status) {}
    record Page(long noteVersion,long linkVersion,List<View> items,Integer nextAfter) {}
    record Backlink(UUID sourceNoteId,String title,long version,long linkCount) {}
    record BacklinkPage(List<Backlink> items,UUID nextAfter) {}
    private final JdbcTemplate jdbc;private final UuidV7 ids;private final Clock clock;
    NoteLinkStore(JdbcTemplate jdbc,Clock clock) {this.jdbc=jdbc;this.clock=clock;ids=new UuidV7(clock);}
    void lock(UUID space) { jdbc.query("SELECT pg_advisory_xact_lock(?)",rs->null,space.getMostSignificantBits()^space.getLeastSignificantBits()); }
    List<Link> current(UUID source) {
        return jdbc.query("SELECT * FROM note_link WHERE source_note_id=? ORDER BY ordinal",(rs,n)->new Link(rs.getObject("link_id",UUID.class),rs.getInt("ordinal"),rs.getInt("start_offset"),rs.getInt("end_offset"),rs.getString("target_token"),rs.getObject("requested_target_id",UUID.class),rs.getString("label"),rs.getString("fragment"),rs.getObject("target_note_id",UUID.class),rs.getString("status")),source);
    }
    void rewrite(NoteStore.Note note,UUID actor,String trace) {
        var prior=new HashMap<String,ArrayDeque<Link>>();for(var link:current(note.noteId()))prior.computeIfAbsent(identity(link.targetToken()),key->new ArrayDeque<>()).add(link);
        var links=new ArrayList<Link>();
        if("ACTIVE".equals(note.state()))for(var token:WikiLinks.parse(note.markdown())) {
            var queue=prior.get(identity(token.target()));Link old=queue==null || queue.isEmpty()?null:queue.removeFirst();UUID target=old==null?null:old.targetNoteId();
            var link=new Link(old==null?ids.next():old.linkId(),links.size(),token.start(),token.end(),token.target(),token.requestedId(),token.label(),token.fragment(),target,target==null?"UNRESOLVED":"RESOLVED");links.add(resolve(note.spaceId(),link));
        }
        jdbc.update("DELETE FROM note_link WHERE source_note_id=?",note.noteId());for(var link:links)insert(note,link);
        publish(note,actor,trace,links);
    }
    private String identity(String token) {return WikiLinks.key(token);}
    private Link resolve(UUID space,Link link) {
        UUID target=link.targetNoteId();String status;
        if(target!=null) {
            var states=jdbc.queryForList("SELECT state FROM note WHERE space_id=? AND note_id=?",String.class,space,target);
            status=states.size()==1 && "ACTIVE".equals(states.getFirst())?"RESOLVED":"TARGET_RETIRED";
        } else {
            var candidates=link.requestedTargetId()==null?jdbc.queryForList("SELECT note_id FROM note WHERE space_id=? AND title_key=? AND state='ACTIVE' ORDER BY note_id LIMIT 2",UUID.class,space,WikiLinks.key(link.targetToken())):jdbc.queryForList("SELECT note_id FROM note WHERE space_id=? AND note_id=? AND state='ACTIVE'",UUID.class,space,link.requestedTargetId());
            status=candidates.isEmpty()?"UNRESOLVED":candidates.size()>1?"AMBIGUOUS":"RESOLVED";if(candidates.size()==1)target=candidates.getFirst();
        }
        return new Link(link.linkId(),link.ordinal(),link.startOffset(),link.endOffset(),link.targetToken(),link.requestedTargetId(),link.label(),link.fragment(),target,status);
    }
    private void insert(NoteStore.Note note,Link link) {
        jdbc.update("INSERT INTO note_link(link_id,space_id,source_note_id,ordinal,start_offset,end_offset,target_token,target_key,requested_target_id,label,fragment,target_note_id,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",link.linkId(),note.spaceId(),note.noteId(),link.ordinal(),link.startOffset(),link.endOffset(),link.targetToken(),WikiLinks.key(link.targetToken()),link.requestedTargetId(),link.label(),link.fragment(),link.targetNoteId(),link.status());
    }
    /** Only sources affected by changed title/ID are reconsidered. Existing bindings never retarget by title. */
    void reconcile(NoteStore.Note target,String oldKey,UUID actor,String trace,java.util.function.Function<UUID,NoteStore.Note> load) {
        var sources=jdbc.queryForList("SELECT DISTINCT source_note_id FROM note_link WHERE space_id=? AND source_note_id<>? AND (target_note_id=? OR (target_note_id IS NULL AND (target_key=? OR target_key=? OR requested_target_id=?))) ORDER BY source_note_id",UUID.class,target.spaceId(),target.noteId(),target.noteId(),oldKey,WikiLinks.key(target.title()),target.noteId());
        for(var source:sources) {
            var before=current(source);var after=before.stream().map(link->resolve(target.spaceId(),link)).toList();if(before.equals(after))continue;
            for(var link:after)jdbc.update("UPDATE note_link SET target_note_id=?,status=? WHERE link_id=?",link.targetNoteId(),link.status(),link.linkId());
            publish(load.apply(source),actor,trace,after);
        }
    }
    private void publish(NoteStore.Note note,UUID actor,String trace,List<Link> links) {
        long version=jdbc.queryForObject("UPDATE note SET link_version=link_version+1,links_dirty=false WHERE note_id=? RETURNING link_version",Long.class,note.noteId());UUID revision=ids.next(),event=ids.next();var now=clock.instant();
        var payload=NoteLinksChangedPayloadV1.newBuilder().setSourceNoteId(note.noteId().toString()).setNoteVersion(note.version()).setLinkVersion(version).setSourceState(note.state()).setRevisionId(revision.toString());
        for(var link:links)payload.addLinks(WikiLinkV1.newBuilder().setLinkId(link.linkId().toString()).setOrdinal(link.ordinal()).setStartOffset(link.startOffset()).setEndOffset(link.endOffset()).setTargetToken(link.targetToken()).setTargetNoteId(link.targetNoteId()==null?"":link.targetNoteId().toString()).setRequestedTargetId(link.requestedTargetId()==null?"":link.requestedTargetId().toString()).setStatus(link.status()).setLabel(link.label()==null?"":link.label()).setFragment(link.fragment()==null?"":link.fragment()));
        var envelope=LinkEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("NoteLinksChanged").setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("content-service").setAggregateType("NoteLinks").setAggregateId(note.noteId().toString()).setSpaceId(note.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.build().toByteArray())).build();
        jdbc.update("INSERT INTO note_link_revision(revision_id,space_id,source_note_id,link_version,note_version,actor_id,occurred_at,payload) VALUES (?,?,?,?,?,?,?,?)",revision,note.spaceId(),note.noteId(),version,note.version(),actor,Timestamp.from(now),payload.build().toByteArray());
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'note-links',?,'NoteLinksChanged',?,?,'content','note-links-events','1',?)",event,note.noteId(),Timestamp.from(now),trace,envelope.toByteArray());
    }
    Page outgoing(NoteStore.Note note,Integer after,int limit) {
        var rows=jdbc.query("SELECT l.*,t.title AS target_title FROM note_link l LEFT JOIN note t ON t.space_id=l.space_id AND t.note_id=l.target_note_id WHERE l.space_id=? AND l.source_note_id=? AND l.ordinal>? ORDER BY l.ordinal LIMIT ?",(rs,n)->new View(rs.getObject("link_id",UUID.class),rs.getInt("ordinal"),rs.getInt("start_offset"),rs.getInt("end_offset"),rs.getString("target_token"),rs.getString("label"),rs.getString("fragment"),rs.getObject("target_note_id",UUID.class),rs.getString("target_title"),rs.getString("status")),note.spaceId(),note.noteId(),after==null?-1:after,limit+1);
        var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));long version=jdbc.queryForObject("SELECT link_version FROM note WHERE note_id=?",Long.class,note.noteId());return new Page(note.version(),version,page,rows.size()>limit?page.getLast().ordinal():null);
    }
    BacklinkPage backlinks(NoteStore.Note note,UUID after,int limit) {
        String sql="SELECT n.note_id,n.title,n.version,count(*) FROM note_link l JOIN note n ON n.space_id=l.space_id AND n.note_id=l.source_note_id WHERE l.space_id=? AND l.target_note_id=? AND l.status='RESOLVED' AND n.state='ACTIVE'"+(after==null?"":" AND n.note_id>?")+" GROUP BY n.note_id,n.title,n.version ORDER BY n.note_id LIMIT ?";
        var args=after==null?new Object[]{note.spaceId(),note.noteId(),limit+1}:new Object[]{note.spaceId(),note.noteId(),after,limit+1};var rows=jdbc.query(sql,(rs,n)->new Backlink(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getLong(4)),args);var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new BacklinkPage(page,rows.size()>limit?page.getLast().sourceNoteId():null);
    }
}
