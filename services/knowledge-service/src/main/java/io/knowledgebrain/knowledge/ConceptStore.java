package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.v1.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ConceptStore {
    record Concept(UUID conceptId,UUID spaceId,String visibility,String state,long version,String title,String description,Instant createdAt,Instant updatedAt) {}
    record Revision(UUID revisionId,UUID conceptId,long version,String state,String title,String description,UUID actorId,Instant occurredAt) {}
    record Summary(UUID conceptId,String title,long version,Instant updatedAt) {}
    record Page(List<Summary> items,UUID nextAfter) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;
    ConceptStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));transaction.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
    static void id(UUID value) { if(value==null || value.version()!=7 || value.variant()!=2)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"UUIDv7 required"); }
    static void text(String title,String description) {
        if(title==null || title.isBlank() || title.codePointCount(0,title.length())>200 || title.indexOf(0)>=0 || description==null || description.indexOf(0)>=0 || description.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Title or Markdown exceeds limits");
    }
    Concept create(UUID spaceId,UUID actor,String title,String description,String trace) {
        return createWithId(spaceId,ids.next(),actor,title,description,trace,"PRIVATE");
    }
    Concept createWithId(UUID spaceId,UUID conceptId,UUID actor,String title,String description,String trace,String visibility) {
        id(spaceId);id(conceptId);id(actor);text(title,description);validateTrace(trace);Instant now=clock.instant();
        return transaction.execute(status->{
            
            jdbc.update("INSERT INTO concept(concept_id,space_id,visibility,state,version,title,description,created_at,updated_at) VALUES (?,?,?,'ACTIVE',1,?,?,?,?)",conceptId,spaceId,visibility,title,description,Timestamp.from(now),Timestamp.from(now));
            Concept note=load(spaceId,conceptId,false);revisionAndEvent(note,actor,trace,"ConceptCreated");return note;
        });
    }
    Concept change(UUID spaceId,UUID conceptId,UUID actor,long expected,String title,String description,boolean retire,String trace) {
        return changeScoped(spaceId,conceptId,actor,expected,title,description,retire,trace,"PRIVATE");
    }
    Concept changeScoped(UUID spaceId,UUID conceptId,UUID actor,long expected,String title,String description,boolean retire,String trace,String visibility) {
        id(spaceId);id(conceptId);id(actor);validateTrace(trace);if(!retire)text(title,description);
        if(expected<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Positive version required");
        return transaction.execute(status->{
            Concept prior=load(spaceId,conceptId,true);
            if(prior==null || !visibility.equals(prior.visibility()) || !"ACTIVE".equals(prior.state()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Concept unavailable");
            if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Concept changed; reload before saving");
            jdbc.update("UPDATE concept SET title=?,description=?,state=?,version=version+1,updated_at=? WHERE concept_id=?",retire?prior.title():title,retire?prior.description():description,retire?"RETIRED":"ACTIVE",Timestamp.from(clock.instant()),conceptId);
            Concept note=load(spaceId,conceptId,false);revisionAndEvent(note,actor,trace,retire?"ConceptRetired":"ConceptUpdated");return note;
        });
    }
    Concept get(UUID spaceId,UUID conceptId) {
        var note=load(spaceId,conceptId,false);
        if(note==null || !"ACTIVE".equals(note.state()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Concept unavailable");return note;
    }
    // Only metadata needed to authorize an ID reference; never return foreign private text.
    record LearningReference(UUID spaceId,long version,String title,String visibility) {}
    LearningReference learningReference(UUID destinationSpace,UUID conceptId){id(destinationSpace);id(conceptId);var rows=jdbc.query("SELECT space_id,version,title,visibility FROM concept WHERE concept_id=? AND state='ACTIVE' AND ((space_id=? AND visibility='PRIVATE') OR visibility='PUBLIC')",(rs,n)->new LearningReference(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4)),conceptId,destinationSpace);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Concept reference unavailable");return rows.getFirst();}
    record Reference(UUID spaceId,String visibility) {}
    Reference reference(UUID destinationSpace,UUID conceptId) {
        var rows=jdbc.query("SELECT space_id,visibility FROM concept WHERE concept_id=? AND state='ACTIVE' AND ((space_id=? AND visibility='PRIVATE') OR visibility='PUBLIC')",
            (rs,n)->new Reference(rs.getObject(1,UUID.class),rs.getString(2)),conceptId,destinationSpace);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Concept unavailable");
        return rows.getFirst();
    }
    private Concept load(UUID spaceId,UUID conceptId,boolean lock) {
        var rows=jdbc.query("SELECT * FROM concept WHERE space_id=? AND concept_id=?"+(lock?" FOR UPDATE":""),(rs,n)->new Concept(rs.getObject("concept_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getString("visibility"),rs.getString("state"),rs.getLong("version"),rs.getString("title"),rs.getString("description"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),spaceId,conceptId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    Page list(UUID spaceId,UUID after,int limit) {
        String sql="SELECT concept_id,title,version,updated_at FROM concept WHERE space_id=? AND state='ACTIVE'"+(after==null?"":" AND concept_id>?")+" ORDER BY concept_id LIMIT ?";
        Object[] args=after==null?new Object[]{spaceId,limit+1}:new Object[]{spaceId,after,limit+1};
        var rows=jdbc.query(sql,(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant()),args);
        boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(page,more?page.getLast().conceptId():null);
    }
    Page retired(UUID spaceId,UUID after,int limit) {
        id(spaceId);if(after!=null)id(after);if(limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page limit");
        String sql="SELECT concept_id,title,version,updated_at FROM concept WHERE space_id=? AND visibility='PRIVATE' AND state='RETIRED'"+(after==null?"":" AND concept_id>?")+" ORDER BY concept_id LIMIT ?";
        Object[] args=after==null?new Object[]{spaceId,limit+1}:new Object[]{spaceId,after,limit+1};
        var rows=jdbc.query(sql,(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant()),args);
        boolean more=rows.size()>limit;var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(page,more?page.getLast().conceptId():null);
    }
    List<Revision> history(UUID spaceId,UUID conceptId,long before,int limit) {
        if(load(spaceId,conceptId,false)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Concept unavailable");
        return jdbc.query("SELECT revision_id,concept_id,version,state,title,description,actor_id,occurred_at FROM concept_revision WHERE concept_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getObject(7,UUID.class),rs.getTimestamp(8).toInstant()),conceptId,before,limit);
    }
    private void revisionAndEvent(Concept note,UUID actor,String trace,String type) {
        UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();
        jdbc.update("INSERT INTO concept_revision(revision_id,concept_id,version,title,description,state,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)",revision,note.conceptId(),note.version(),note.title(),note.description(),note.state(),actor,Timestamp.from(now));
        var payload=ConceptChangedPayloadV1.newBuilder().setConceptId(note.conceptId().toString()).setRevisionId(revision.toString()).setVersion(note.version()).setState(note.state()).setVisibility(note.visibility()).setTitle(note.title()).setDescription(note.description()).build();
        var envelope=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("knowledge-service").setAggregateType("Concept").setAggregateId(note.conceptId().toString()).setSpaceId(note.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'concept',?,?,?,?,'knowledge','concept-events','1',?)",event,note.conceptId(),type,Timestamp.from(now),trace,envelope.toByteArray());
        if(note.visibility().equals("PUBLIC")){var json=tools.jackson.databind.json.JsonMapper.builder().build();PublicKnowledgeEvents.publish(jdbc,ids,clock,revision,note.conceptId(),note.spaceId(),actor,trace,"CONCEPT",note.version(),note.state(),note.title(),json.createObjectNode().put("description",note.description()),json.createObjectNode());}
    }
    private static void validateTrace(String trace){if(trace==null || !trace.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Valid trace required");}
}
