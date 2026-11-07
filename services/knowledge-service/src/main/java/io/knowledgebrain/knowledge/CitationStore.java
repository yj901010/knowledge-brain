package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.citation.v2.ClaimCitationChangedPayloadV2;
import io.knowledgebrain.contracts.knowledge.citation.v2.EventEnvelopeV2;
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

final class CitationStore {
    record Reference(UUID sourceId,long sourceVersion,long claimVersion) {}
    record ClaimSnapshot(UUID revisionId,long version,UUID subjectConceptId,UUID predicateConceptId,ClaimStore.ObjectValue object) {}
    record SourceSnapshot(UUID sourceId,UUID revisionId,long version,String title,String url,String citation,String licenseNote) {}
    record Citation(UUID citationId,UUID spaceId,UUID claimId,UUID subjectConceptId,ClaimSnapshot claim,SourceSnapshot source,
                    String visibility,String state,long version,Instant createdAt,Instant updatedAt) {}
    record Revision(UUID revisionId,UUID citationId,long version,String state,ClaimSnapshot claim,SourceSnapshot source,UUID actorId,Instant occurredAt) {}
    record Page(List<Citation> items,UUID nextAfter) {}
    record SourcePin(UUID sourceId,UUID revisionId,long version) {}
    record SourceCitation(UUID citationId,UUID spaceId,UUID subjectConceptId,String subjectTitle,UUID claimId,long version,
                          ClaimSnapshot claim,SourcePin source,long currentClaimVersion,String visibility,String state,
                          Instant createdAt,Instant updatedAt) {}
    record SourcePage(List<SourceCitation> items,UUID nextAfter) {}
    private record ClaimRow(UUID id,UUID subject,UUID predicate,UUID object,long version,String state,String visibility) {}
    private record SourceRow(UUID id,long version,String state,String visibility) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;

    CitationStore(DataSource source,Clock clock) {
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);
    }
    static Reference reference(UUID sourceId,long sourceVersion,long claimVersion) {
        ConceptStore.id(sourceId);if(sourceVersion<1||claimVersion<1)throw bad("Positive source and Claim versions required");
        return new Reference(sourceId,sourceVersion,claimVersion);
    }
    Citation create(UUID space,UUID subject,UUID claim,UUID actor,Reference reference,String trace) {
        identify(space,subject,claim);ConceptStore.id(actor);validate(reference);trace(trace);
        return transaction.execute(status->{
            ClaimRow current=lockClaim(space,subject,claim,reference.claimVersion());
            var sources=lockSources(space,Set.of(reference.sourceId()));SourceRow source=selected(sources,reference);
            available(space,claim,source.id(),null);
            UUID citation=ids.next();Instant now=clock.instant();UUID claimRevision=claimRevision(current),sourceRevision=sourceRevision(source);
            jdbc.update("INSERT INTO claim_citation(citation_id,space_id,subject_concept_id,claim_id,source_id,claim_revision_id,source_revision_id,visibility,state,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PRIVATE','ACTIVE',1,?,?)",
                citation,space,subject,claim,source.id(),claimRevision,sourceRevision,Timestamp.from(now),Timestamp.from(now));
            Citation result=load(space,subject,claim,citation,false);publish(result,actor,trace,"ClaimCitationCreated");return result;
        });
    }
    Citation change(UUID space,UUID subject,UUID claim,UUID citation,UUID actor,long expected,Reference reference,boolean retire,String trace) {
        identify(space,subject,claim);ConceptStore.id(citation);ConceptStore.id(actor);trace(trace);
        if(expected<1)throw bad("Positive citation version required");if(!retire)validate(reference);
        return transaction.execute(status->{
            Citation snapshot=load(space,subject,claim,citation,false);
            if(snapshot==null||!"ACTIVE".equals(snapshot.state()))throw missing();
            if(snapshot.version()!=expected)throw stale();
            ClaimRow current=lockClaim(space,subject,claim,retire?null:reference.claimVersion());
            var references=new HashSet<UUID>();references.add(snapshot.source().sourceId());if(!retire)references.add(reference.sourceId());
            var sources=lockSources(space,references);
            Citation prior=load(space,subject,claim,citation,true);
            if(prior==null||!"ACTIVE".equals(prior.state()))throw missing();
            // A changed source reference invalidates the lock set, even with a guessed future ETag.
            if(prior.version()!=snapshot.version()||prior.version()!=expected)throw stale();
            if(prior.version()==Long.MAX_VALUE)throw new ResponseStatusException(HttpStatus.CONFLICT,"Citation version limit reached");
            UUID sourceId=prior.source().sourceId(),claimRevision=prior.claim().revisionId(),sourceRevision=prior.source().revisionId();
            if(!retire){SourceRow source=selected(sources,reference);sourceId=source.id();available(space,claim,sourceId,citation);claimRevision=claimRevision(current);sourceRevision=sourceRevision(source);}
            jdbc.update("UPDATE claim_citation SET source_id=?,claim_revision_id=?,source_revision_id=?,state=?,version=version+1,updated_at=? WHERE citation_id=?",
                sourceId,claimRevision,sourceRevision,retire?"RETIRED":"ACTIVE",Timestamp.from(clock.instant()),citation);
            Citation result=load(space,subject,claim,citation,false);publish(result,actor,trace,retire?"ClaimCitationRetired":"ClaimCitationUpdated");return result;
        });
    }
    Citation get(UUID space,UUID subject,UUID claim,UUID citation) {
        identify(space,subject,claim);ConceptStore.id(citation);
        var rows=jdbc.query(CITATION_SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.citation_id=? AND c.visibility='PRIVATE' AND c.state='ACTIVE' AND "+ACTIVE_SCOPE,(rs,n)->read(rs),space,subject,claim,citation);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    Page list(UUID space,UUID subject,UUID claim,UUID after,int limit) {
        identify(space,subject,claim);if(after!=null)ConceptStore.id(after);limit(limit,100);effective(space,subject,claim);
        var args=new ArrayList<Object>(List.of(space,subject,claim));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(CITATION_SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.visibility='PRIVATE' AND c.state='ACTIVE' AND "+ACTIVE_SCOPE+(after==null?"":" AND c.citation_id>?")+" ORDER BY c.citation_id LIMIT ?",(rs,n)->read(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,more?items.getLast().citationId():null);
    }
    Page retired(UUID space,UUID subject,UUID claim,UUID after,int limit) {
        identify(space,subject,claim);if(after!=null)ConceptStore.id(after);limit(limit,100);
        // A retained private Claim/subject identity is sufficient; lifecycle does not erase audit access.
        if(jdbc.queryForList("SELECT k.claim_id FROM claim k JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id WHERE k.space_id=? AND k.subject_concept_id=? AND k.claim_id=? AND k.visibility='PRIVATE' AND s.visibility='PRIVATE'",UUID.class,space,subject,claim).isEmpty())throw missing();
        var args=new ArrayList<Object>(List.of(space,subject,claim));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(CITATION_SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.visibility='PRIVATE' AND c.state='RETIRED'"+(after==null?"":" AND c.citation_id>?")+" ORDER BY c.citation_id LIMIT ?",(rs,n)->read(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,more?items.getLast().citationId():null);
    }
    SourcePage bySource(UUID space,UUID source,UUID after,int limit) {
        ConceptStore.id(space);ConceptStore.id(source);if(after!=null)ConceptStore.id(after);limit(limit,100);
        // Retirement does not invalidate a Source's retained identity or its pinned citations.
        if(jdbc.queryForList("SELECT source_id FROM knowledge_source WHERE space_id=? AND source_id=? AND visibility='PRIVATE'",UUID.class,space,source).isEmpty())throw missing();
        var args=new ArrayList<Object>(List.of(space,source));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query("SELECT c.*,s.title AS subject_title,k.version AS current_claim_version,"+
            "cr.revision_id AS cr_id,cr.version AS cr_version,cr.subject_concept_id AS cr_subject,cr.predicate_concept_id AS cr_predicate,cr.object_type AS cr_object_type,cr.object_value AS cr_value,cr.object_concept_id AS cr_object,"+
            "sr.source_id AS sr_source,sr.revision_id AS sr_id,sr.version AS sr_version "+
            "FROM claim_citation c JOIN knowledge_source src ON src.space_id=c.space_id AND src.source_id=c.source_id "+
            "JOIN claim k ON k.space_id=c.space_id AND k.subject_concept_id=c.subject_concept_id AND k.claim_id=c.claim_id "+
            "JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id "+
            "JOIN concept p ON p.space_id=k.space_id AND p.concept_id=k.predicate_concept_id "+
            "LEFT JOIN concept o ON o.space_id=k.space_id AND o.concept_id=k.object_concept_id "+
            "JOIN claim_revision cr ON cr.claim_id=c.claim_id AND cr.revision_id=c.claim_revision_id "+
            "JOIN source_revision sr ON sr.source_id=c.source_id AND sr.revision_id=c.source_revision_id "+
            "WHERE c.space_id=? AND c.source_id=? AND c.visibility='PRIVATE' AND c.state='ACTIVE' AND src.visibility='PRIVATE' "+
            "AND k.visibility='PRIVATE' AND k.state='ACTIVE' AND s.visibility='PRIVATE' AND s.state='ACTIVE' "+
            "AND p.visibility='PRIVATE' AND p.state='ACTIVE' AND (k.object_type<>'CONCEPT' OR (o.visibility='PRIVATE' AND o.state='ACTIVE')) "+
            (after==null?"":"AND c.citation_id>? ")+"ORDER BY c.citation_id LIMIT ?",
            (rs,n)->new SourceCitation(rs.getObject("citation_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getObject("subject_concept_id",UUID.class),rs.getString("subject_title"),rs.getObject("claim_id",UUID.class),rs.getLong("version"),claimSnapshot(rs),
                new SourcePin(rs.getObject("sr_source",UUID.class),rs.getObject("sr_id",UUID.class),rs.getLong("sr_version")),rs.getLong("current_claim_version"),rs.getString("visibility"),rs.getString("state"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new SourcePage(items,more?items.getLast().citationId():null);
    }
    List<Revision> history(UUID space,UUID subject,UUID claim,UUID citation,long before,int limit) {
        identify(space,subject,claim);ConceptStore.id(citation);limit(limit,10);if(before<1)throw bad("Invalid citation history cursor");
        if(load(space,subject,claim,citation,false)==null)throw missing();
        return jdbc.query("SELECT r.*,"+SNAPSHOT_COLUMNS+" FROM citation_revision r JOIN claim_revision cr ON cr.claim_id=r.claim_id AND cr.revision_id=r.claim_revision_id JOIN source_revision sr ON sr.source_id=r.source_id AND sr.revision_id=r.source_revision_id WHERE r.citation_id=? AND r.version<? ORDER BY r.version DESC LIMIT ?",
            (rs,n)->new Revision(rs.getObject("revision_id",UUID.class),rs.getObject("citation_id",UUID.class),rs.getLong("version"),rs.getString("state"),claimSnapshot(rs),sourceSnapshot(rs),rs.getObject("actor_id",UUID.class),rs.getTimestamp("occurred_at").toInstant()),citation,before,limit);
    }
    private ClaimRow lockClaim(UUID space,UUID subject,UUID claim,Long expected) {
        ClaimRow snapshot=claimRow(space,subject,claim,false);if(snapshot==null||!"ACTIVE".equals(snapshot.state())||!"PRIVATE".equals(snapshot.visibility()))throw missing();
        var references=new HashSet<>(List.of(snapshot.subject(),snapshot.predicate()));if(snapshot.object()!=null)references.add(snapshot.object());var args=new ArrayList<Object>();args.add(space);args.addAll(references);
        var rows=jdbc.query("SELECT concept_id,state,visibility FROM concept WHERE space_id=? AND concept_id IN ("+String.join(",",Collections.nCopies(references.size(),"?"))+") ORDER BY concept_id FOR UPDATE",
            (rs,n)->new String[]{rs.getString("state"),rs.getString("visibility")},args.toArray());
        if(rows.size()!=references.size()||rows.stream().anyMatch(row->!"ACTIVE".equals(row[0])||!"PRIVATE".equals(row[1])))throw missing();
        ClaimRow current=claimRow(space,subject,claim,true);if(current==null||!"ACTIVE".equals(current.state())||!"PRIVATE".equals(current.visibility()))throw missing();
        if(current.version()!=snapshot.version()||expected!=null&&current.version()!=expected)throw stale();return current;
    }
    private ClaimRow claimRow(UUID space,UUID subject,UUID claim,boolean lock) {
        var rows=jdbc.query("SELECT claim_id,subject_concept_id,predicate_concept_id,object_concept_id,version,state,visibility FROM claim WHERE space_id=? AND subject_concept_id=? AND claim_id=?"+(lock?" FOR UPDATE":""),
            (rs,n)->new ClaimRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getLong(5),rs.getString(6),rs.getString(7)),space,subject,claim);return rows.isEmpty()?null:rows.getFirst();
    }
    private void effective(UUID space,UUID subject,UUID claim) {
        var rows=jdbc.queryForList("SELECT k.claim_id FROM claim k JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id JOIN concept p ON p.space_id=k.space_id AND p.concept_id=k.predicate_concept_id LEFT JOIN concept o ON o.space_id=k.space_id AND o.concept_id=k.object_concept_id WHERE k.space_id=? AND k.subject_concept_id=? AND k.claim_id=? AND k.visibility='PRIVATE' AND k.state='ACTIVE' AND s.state='ACTIVE' AND s.visibility='PRIVATE' AND p.state='ACTIVE' AND p.visibility='PRIVATE' AND (k.object_type<>'CONCEPT' OR (o.state='ACTIVE' AND o.visibility='PRIVATE'))",UUID.class,space,subject,claim);
        if(rows.isEmpty())throw missing();
    }
    private Map<UUID,SourceRow> lockSources(UUID space,Set<UUID> references) {
        var args=new ArrayList<Object>();args.add(space);args.addAll(references);
        var rows=jdbc.query("SELECT source_id,version,state,visibility FROM knowledge_source WHERE space_id=? AND source_id IN ("+String.join(",",Collections.nCopies(references.size(),"?"))+") ORDER BY source_id FOR UPDATE",
            (rs,n)->new SourceRow(rs.getObject(1,UUID.class),rs.getLong(2),rs.getString(3),rs.getString(4)),args.toArray());
        if(rows.size()!=references.size()||rows.stream().anyMatch(row->!"PRIVATE".equals(row.visibility())))throw missing();
        var result=new HashMap<UUID,SourceRow>();rows.forEach(row->result.put(row.id(),row));return result;
    }
    private SourceRow selected(Map<UUID,SourceRow> sources,Reference reference) {
        SourceRow source=sources.get(reference.sourceId());if(source==null||!"ACTIVE".equals(source.state()))throw missing();
        if(source.version()!=reference.sourceVersion())throw stale();return source;
    }
    private UUID claimRevision(ClaimRow claim) {
        var rows=jdbc.queryForList("SELECT revision_id FROM claim_revision WHERE claim_id=? AND version=? AND state='ACTIVE' AND verification_state='DRAFT'",UUID.class,claim.id(),claim.version());
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }
    private UUID sourceRevision(SourceRow source) {
        var rows=jdbc.queryForList("SELECT revision_id FROM source_revision WHERE source_id=? AND version=? AND state='ACTIVE'",UUID.class,source.id(),source.version());
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }
    private void available(UUID space,UUID claim,UUID source,UUID exclude) {
        var args=new ArrayList<Object>(List.of(space,claim,source));if(exclude!=null)args.add(exclude);
        if(!jdbc.queryForList("SELECT citation_id FROM claim_citation WHERE space_id=? AND claim_id=? AND source_id=? AND state='ACTIVE'"+(exclude==null?"":" AND citation_id<>?"),UUID.class,args.toArray()).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"This source already cites the Claim");
    }
    private static final String SNAPSHOT_COLUMNS="cr.revision_id AS cr_id,cr.version AS cr_version,cr.subject_concept_id AS cr_subject,cr.predicate_concept_id AS cr_predicate,cr.object_type AS cr_object_type,cr.object_value AS cr_value,cr.object_concept_id AS cr_object,sr.source_id AS sr_source,sr.revision_id AS sr_id,sr.version AS sr_version,sr.title AS sr_title,sr.url AS sr_url,sr.citation AS sr_citation,sr.license_note AS sr_license";
    private static final String ACTIVE_SCOPE="EXISTS (SELECT 1 FROM claim k JOIN concept s ON s.space_id=k.space_id AND s.concept_id=k.subject_concept_id JOIN concept p ON p.space_id=k.space_id AND p.concept_id=k.predicate_concept_id LEFT JOIN concept o ON o.space_id=k.space_id AND o.concept_id=k.object_concept_id WHERE k.space_id=c.space_id AND k.subject_concept_id=c.subject_concept_id AND k.claim_id=c.claim_id AND k.visibility='PRIVATE' AND k.state='ACTIVE' AND s.state='ACTIVE' AND s.visibility='PRIVATE' AND p.state='ACTIVE' AND p.visibility='PRIVATE' AND (k.object_type<>'CONCEPT' OR (o.state='ACTIVE' AND o.visibility='PRIVATE')))";
    private static final String CITATION_SELECT="SELECT c.*,"+SNAPSHOT_COLUMNS+" FROM claim_citation c JOIN claim_revision cr ON cr.claim_id=c.claim_id AND cr.revision_id=c.claim_revision_id JOIN source_revision sr ON sr.source_id=c.source_id AND sr.revision_id=c.source_revision_id";
    private Citation load(UUID space,UUID subject,UUID claim,UUID citation,boolean lock) {
        var rows=jdbc.query(CITATION_SELECT+" WHERE c.space_id=? AND c.subject_concept_id=? AND c.claim_id=? AND c.citation_id=? AND c.visibility='PRIVATE'"+(lock?" FOR UPDATE OF c":""),(rs,n)->read(rs),space,subject,claim,citation);return rows.isEmpty()?null:rows.getFirst();
    }
    private static ClaimSnapshot claimSnapshot(ResultSet rs)throws SQLException{return new ClaimSnapshot(rs.getObject("cr_id",UUID.class),rs.getLong("cr_version"),rs.getObject("cr_subject",UUID.class),rs.getObject("cr_predicate",UUID.class),new ClaimStore.ObjectValue(rs.getString("cr_object_type"),rs.getObject("cr_object",UUID.class),rs.getString("cr_value")));}
    private static SourceSnapshot sourceSnapshot(ResultSet rs)throws SQLException{return new SourceSnapshot(rs.getObject("sr_source",UUID.class),rs.getObject("sr_id",UUID.class),rs.getLong("sr_version"),rs.getString("sr_title"),rs.getString("sr_url"),rs.getString("sr_citation"),rs.getString("sr_license"));}
    private static Citation read(ResultSet rs)throws SQLException{return new Citation(rs.getObject("citation_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getObject("claim_id",UUID.class),rs.getObject("subject_concept_id",UUID.class),claimSnapshot(rs),sourceSnapshot(rs),rs.getString("visibility"),rs.getString("state"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());}
    private void publish(Citation value,UUID actor,String trace,String type) {
        UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();var claim=value.claim();var source=value.source();
        jdbc.update("INSERT INTO citation_revision(revision_id,citation_id,version,state,claim_id,source_id,claim_revision_id,source_revision_id,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)",revision,value.citationId(),value.version(),value.state(),value.claimId(),source.sourceId(),claim.revisionId(),source.revisionId(),actor,Timestamp.from(now));
        var payload=ClaimCitationChangedPayloadV2.newBuilder().setCitationId(value.citationId().toString()).setClaimId(value.claimId().toString()).setSubjectConceptId(value.subjectConceptId().toString())
            .setClaimRevisionId(claim.revisionId().toString()).setClaimVersion(claim.version()).setPredicateConceptId(claim.predicateConceptId().toString()).setObjectType(claim.object().type()).setObjectConceptId(claim.object().conceptId()==null?"":claim.object().conceptId().toString()).setObjectValue(claim.object().value()==null?"":claim.object().value())
            .setSourceId(source.sourceId().toString()).setSourceRevisionId(source.revisionId().toString()).setSourceVersion(source.version()).setTitle(source.title()).setUrl(source.url()==null?"":source.url()).setCitation(source.citation()).setLicenseNote(source.licenseNote())
            .setRevisionId(revision.toString()).setVersion(value.version()).setState(value.state()).setVisibility(value.visibility()).build();
        var envelope=EventEnvelopeV2.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(2).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("knowledge-service").setAggregateType("ClaimCitation").setAggregateId(value.citationId().toString()).setSpaceId(value.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'citation-v2',?,?,?,?,'knowledge','citation-events-v2','1',?)",event,value.citationId(),type,Timestamp.from(now),trace,envelope.toByteArray());
    }
    private static void identify(UUID space,UUID subject,UUID claim){ConceptStore.id(space);ConceptStore.id(subject);ConceptStore.id(claim);}
    private static void validate(Reference value){if(value==null)throw bad("Source reference required");reference(value.sourceId(),value.sourceVersion(),value.claimVersion());}
    private static void limit(int value,int maximum){if(value<1||value>maximum)throw bad("Invalid citation page limit");}
    private static void trace(String value){if(value==null||!value.matches("[0-9a-f]{32}")||value.equals("0".repeat(32)))throw new IllegalArgumentException("Valid trace required");}
    private static ResponseStatusException stale(){return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Citation, Claim or source changed; inspect before saving");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Private Claim citation unavailable");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
