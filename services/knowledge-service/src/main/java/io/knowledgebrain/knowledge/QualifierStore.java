package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.qualifier.v2.ClaimQualifierChangedPayloadV2;
import io.knowledgebrain.contracts.knowledge.qualifier.v2.EventEnvelopeV2;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;


final class QualifierStore {
    record Value(String type,String value) {}
    record Edit(UUID predicateConceptId,Value value,long claimVersion) {}
    record ClaimSnapshot(UUID revisionId,long version,UUID subjectConceptId,UUID predicateConceptId,ClaimStore.ObjectValue object) {}
    record Qualifier(UUID qualifierId,UUID spaceId,UUID claimId,UUID subjectConceptId,ClaimSnapshot claim,
                     UUID predicateConceptId,Value value,String visibility,String state,long version,Instant createdAt,Instant updatedAt) {}
    record Revision(UUID revisionId,UUID qualifierId,long version,String state,ClaimSnapshot claim,
                    UUID predicateConceptId,Value value,UUID actorId,Instant occurredAt) {}
    record Page(List<Qualifier> items,UUID nextAfter) {}
    private record ClaimRow(UUID id,UUID subject,UUID predicate,UUID object,long version,String state,String visibility) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;
    QualifierStore(DataSource source,Clock clock) {
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);
    }
    static Value value(String type,String text) {var v=KnowledgeValue.value(type,text);return new Value(v.type(),v.value());}
    static Edit edit(UUID predicate,Value value,long claimVersion) {
        ConceptStore.id(predicate);if(value==null||claimVersion<1)throw bad("Qualifier predicate, value and positive Claim version required");
        return new Edit(predicate,value(value.type(),value.value()),claimVersion);
    }
    Qualifier create(UUID space,UUID subject,UUID claim,UUID actor,Edit input,String trace) {
        identify(space,subject,claim);ConceptStore.id(actor);trace(trace);Edit e=validate(input);
        return transaction.execute(status->{
            ClaimRow current=lockClaim(space,subject,claim,e);
            available(space,claim,e,null);
            UUID qualifier=ids.next();Instant now=clock.instant();
            jdbc.update("INSERT INTO claim_qualifier(qualifier_id,space_id,subject_concept_id,claim_id,claim_revision_id,predicate_concept_id,value_type,value_text,visibility,state,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,'PRIVATE','ACTIVE',1,?,?)",
                qualifier,space,subject,claim,claimRevision(current),e.predicateConceptId(),e.value().type(),e.value().value(),Timestamp.from(now),Timestamp.from(now));
            Qualifier result=load(space,subject,claim,qualifier,false);publish(result,actor,trace,"ClaimQualifierCreated");return result;
        });
    }
    Qualifier change(UUID space,UUID subject,UUID claim,UUID qualifier,UUID actor,long expected,Edit input,boolean retire,String trace) {
        identify(space,subject,claim);ConceptStore.id(qualifier);ConceptStore.id(actor);trace(trace);if(expected<1)throw bad("Positive qualifier version required");
        Edit e=retire?null:validate(input);
        return transaction.execute(status->{
            // All Concept locks precede the Claim lock, shared with Claim and Citation writes.
            ClaimRow current=lockClaim(space,subject,claim,e);
            Qualifier prior=load(space,subject,claim,qualifier,true);
            if(prior==null||!"ACTIVE".equals(prior.state()))throw missing();
            if(prior.version()!=expected)throw stale();
            if(expected==Long.MAX_VALUE)throw new ResponseStatusException(HttpStatus.CONFLICT,"Qualifier version limit reached");
            if(!retire)available(space,claim,e,qualifier);
            jdbc.update("UPDATE claim_qualifier SET claim_revision_id=?,predicate_concept_id=?,value_type=?,value_text=?,state=?,version=version+1,updated_at=? WHERE qualifier_id=?",
                retire?prior.claim().revisionId():claimRevision(current),retire?prior.predicateConceptId():e.predicateConceptId(),
                retire?prior.value().type():e.value().type(),retire?prior.value().value():e.value().value(),retire?"RETIRED":"ACTIVE",Timestamp.from(clock.instant()),qualifier);
            Qualifier result=load(space,subject,claim,qualifier,false);publish(result,actor,trace,retire?"ClaimQualifierRetired":"ClaimQualifierUpdated");return result;
        });
    }
    Qualifier get(UUID space,UUID subject,UUID claim,UUID qualifier) {
        identify(space,subject,claim);ConceptStore.id(qualifier);effective(space,subject,claim);
        Qualifier result=load(space,subject,claim,qualifier,false);if(result==null||!"ACTIVE".equals(result.state()))throw missing();return result;
    }
    Page list(UUID space,UUID subject,UUID claim,UUID after,int limit) {
        identify(space,subject,claim);if(after!=null)ConceptStore.id(after);limit(limit,100);effective(space,subject,claim);
        var args=new ArrayList<Object>(List.of(space,subject,claim));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.visibility='PRIVATE' AND c.state='ACTIVE'"+(after==null?"":" AND c.qualifier_id>?")+" ORDER BY c.qualifier_id LIMIT ?",(rs,n)->read(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,more?items.getLast().qualifierId():null);
    }
    Page retired(UUID space,UUID subject,UUID claim,UUID after,int limit) {
        identify(space,subject,claim);if(after!=null)ConceptStore.id(after);limit(limit,100);
        // Retained private parent identities preserve audit access across retirement.
        if(jdbc.queryForList("SELECT k.claim_id FROM claim k JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id WHERE k.space_id=? AND k.subject_concept_id=? AND k.claim_id=? AND k.visibility='PRIVATE' AND s.visibility='PRIVATE'",UUID.class,space,subject,claim).isEmpty())throw missing();
        var args=new ArrayList<Object>(List.of(space,subject,claim));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.visibility='PRIVATE' AND c.state='RETIRED'"+(after==null?"":" AND c.qualifier_id>?")+" ORDER BY c.qualifier_id LIMIT ?",(rs,n)->read(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,more?items.getLast().qualifierId():null);
    }
    List<Revision> history(UUID space,UUID subject,UUID claim,UUID qualifier,long before,int limit) {
        identify(space,subject,claim);ConceptStore.id(qualifier);limit(limit,10);if(before<1)throw bad("Invalid history cursor");
        if(load(space,subject,claim,qualifier,false)==null)throw missing();
        return jdbc.query("SELECT r.*,"+SNAPSHOT_COLUMNS+" FROM qualifier_revision r JOIN claim_revision cr ON cr.claim_id=r.claim_id AND cr.revision_id=r.claim_revision_id WHERE r.qualifier_id=? AND r.version<? ORDER BY r.version DESC LIMIT ?",
            (rs,n)->new Revision(rs.getObject("revision_id",UUID.class),rs.getObject("qualifier_id",UUID.class),rs.getLong("version"),rs.getString("state"),claimSnapshot(rs),rs.getObject("predicate_concept_id",UUID.class),new Value(rs.getString("value_type"),rs.getString("value_text")),rs.getObject("actor_id",UUID.class),rs.getTimestamp("occurred_at").toInstant()),qualifier,before,limit);
    }
    private ClaimRow lockClaim(UUID space,UUID subject,UUID claim,Edit edit) {
        ClaimRow snapshot=claimRow(space,subject,claim,false);if(snapshot==null||!"ACTIVE".equals(snapshot.state())||!"PRIVATE".equals(snapshot.visibility()))throw missing();
        var references=new HashSet<>(List.of(snapshot.subject(),snapshot.predicate()));if(snapshot.object()!=null)references.add(snapshot.object());
        if(edit!=null){references.add(edit.predicateConceptId());if("CONCEPT".equals(edit.value().type()))references.add(UUID.fromString(edit.value().value()));}
        var args=new ArrayList<Object>();args.add(space);args.addAll(references);
        var rows=jdbc.query("SELECT concept_id,state,visibility FROM concept WHERE space_id=? AND concept_id IN ("+String.join(",",Collections.nCopies(references.size(),"?"))+") ORDER BY concept_id FOR UPDATE",
            (rs,n)->new String[]{rs.getString("state"),rs.getString("visibility")},args.toArray());
        if(rows.size()!=references.size()||rows.stream().anyMatch(row->!"ACTIVE".equals(row[0])||!"PRIVATE".equals(row[1])))throw missing();
        ClaimRow current=claimRow(space,subject,claim,true);if(current==null||!"ACTIVE".equals(current.state())||!"PRIVATE".equals(current.visibility()))throw missing();
        if(current.version()!=snapshot.version()||edit!=null&&current.version()!=edit.claimVersion())throw stale();return current;
    }
    private ClaimRow claimRow(UUID space,UUID subject,UUID claim,boolean lock) {
        var rows=jdbc.query("SELECT claim_id,subject_concept_id,predicate_concept_id,object_concept_id,version,state,visibility FROM claim WHERE space_id=? AND subject_concept_id=? AND claim_id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new ClaimRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getLong(5),rs.getString(6),rs.getString(7)),space,subject,claim);return rows.isEmpty()?null:rows.getFirst();
    }
    private void effective(UUID space,UUID subject,UUID claim) {
        var rows=jdbc.queryForList("SELECT k.claim_id FROM claim k JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id JOIN concept p ON p.space_id=k.space_id AND p.concept_id=k.predicate_concept_id LEFT JOIN concept o ON o.space_id=k.space_id AND o.concept_id=k.object_concept_id WHERE k.space_id=? AND k.subject_concept_id=? AND k.claim_id=? AND k.visibility='PRIVATE' AND k.state='ACTIVE' AND s.state='ACTIVE' AND s.visibility='PRIVATE' AND p.state='ACTIVE' AND p.visibility='PRIVATE' AND (k.object_type<>'CONCEPT' OR (o.state='ACTIVE' AND o.visibility='PRIVATE'))",UUID.class,space,subject,claim);
        if(rows.isEmpty())throw missing();
    }
    private UUID claimRevision(ClaimRow claim) {
        var rows=jdbc.queryForList("SELECT revision_id FROM claim_revision WHERE claim_id=? AND version=? AND state='ACTIVE' AND verification_state='DRAFT'",UUID.class,claim.id(),claim.version());
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }

    private void available(UUID space,UUID claim,Edit e,UUID exclude) {
        var args=new ArrayList<Object>(List.of(space,claim,e.predicateConceptId(),e.value().type(),e.value().value()));if(exclude!=null)args.add(exclude);
        if(!jdbc.queryForList("SELECT qualifier_id FROM claim_qualifier WHERE space_id=? AND claim_id=? AND predicate_concept_id=? AND value_type=? AND value_text=? AND state='ACTIVE'"+(exclude==null?"":" AND qualifier_id<>?"),UUID.class,args.toArray()).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This condition already exists on the Claim");
    }
    private static final String SNAPSHOT_COLUMNS="cr.revision_id AS cr_id,cr.version AS cr_version,cr.subject_concept_id AS cr_subject,cr.predicate_concept_id AS cr_predicate,cr.object_type AS cr_object_type,cr.object_value AS cr_value,cr.object_concept_id AS cr_object";
    private static final String SELECT="SELECT c.*,"+SNAPSHOT_COLUMNS+" FROM claim_qualifier c JOIN claim_revision cr ON cr.claim_id=c.claim_id AND cr.revision_id=c.claim_revision_id";
    private Qualifier load(UUID space,UUID subject,UUID claim,UUID qualifier,boolean lock) {
        var rows=jdbc.query(SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.qualifier_id=? AND c.visibility='PRIVATE'"+(lock?" FOR UPDATE OF c":""),(rs,n)->read(rs),space,subject,claim,qualifier);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private static ClaimSnapshot claimSnapshot(ResultSet rs)throws SQLException{return new ClaimSnapshot(rs.getObject("cr_id",UUID.class),rs.getLong("cr_version"),rs.getObject("cr_subject",UUID.class),rs.getObject("cr_predicate",UUID.class),new ClaimStore.ObjectValue(rs.getString("cr_object_type"),rs.getObject("cr_object",UUID.class),rs.getString("cr_value")));}
    private static Qualifier read(ResultSet rs)throws SQLException{return new Qualifier(rs.getObject("qualifier_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getObject("claim_id",UUID.class),rs.getObject("subject_concept_id",UUID.class),claimSnapshot(rs),rs.getObject("predicate_concept_id",UUID.class),new Value(rs.getString("value_type"),rs.getString("value_text")),rs.getString("visibility"),rs.getString("state"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());}
    private void publish(Qualifier value,UUID actor,String trace,String type) {
        UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();var claim=value.claim();
        jdbc.update("INSERT INTO qualifier_revision(revision_id,qualifier_id,version,state,claim_id,claim_revision_id,predicate_concept_id,value_type,value_text,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            revision,value.qualifierId(),value.version(),value.state(),value.claimId(),claim.revisionId(),value.predicateConceptId(),value.value().type(),value.value().value(),actor,Timestamp.from(now));
        var payload=ClaimQualifierChangedPayloadV2.newBuilder().setQualifierId(value.qualifierId().toString()).setClaimId(value.claimId().toString()).setSubjectConceptId(value.subjectConceptId().toString())
            .setClaimRevisionId(claim.revisionId().toString()).setClaimVersion(claim.version()).setClaimPredicateConceptId(claim.predicateConceptId().toString()).setClaimObjectConceptId(claim.object().conceptId()==null?"":claim.object().conceptId().toString()).setClaimObjectType(claim.object().type()).setClaimObjectValue(claim.object().value()==null?"":claim.object().value())
            .setPredicateConceptId(value.predicateConceptId().toString()).setValueType(value.value().type()).setValueText(value.value().value())
            .setRevisionId(revision.toString()).setVersion(value.version()).setState(value.state()).setVisibility(value.visibility()).build();
        var envelope=EventEnvelopeV2.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(2).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("knowledge-service").setAggregateType("ClaimQualifier").setAggregateId(value.qualifierId().toString()).setSpaceId(value.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'qualifier-v2',?,?,?,?,'knowledge','qualifier-events-v2','1',?)",event,value.qualifierId(),type,Timestamp.from(now),trace,envelope.toByteArray());
    }
    private static void identify(UUID space,UUID subject,UUID claim){ConceptStore.id(space);ConceptStore.id(subject);ConceptStore.id(claim);}
    private static Edit validate(Edit e){if(e==null)throw bad("Qualifier required");return edit(e.predicateConceptId(),e.value(),e.claimVersion());}
    private static void limit(int value,int maximum){if(value<1||value>maximum)throw bad("Invalid qualifier page limit");}
    private static void trace(String value){if(value==null||!value.matches("[0-9a-f]{32}")||value.equals("0".repeat(32)))throw new IllegalArgumentException("Valid trace required");}
    private static ResponseStatusException stale(){return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Qualifier or Claim changed; inspect before saving");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Private Claim qualifier unavailable");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
