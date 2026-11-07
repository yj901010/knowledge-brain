package io.knowledgebrain.knowledge;
import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.mapping.v1.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

final class ExternalMappingStore {
    record Edit(String system,String code,String systemVersion,String label,UUID sourceId,long sourceVersion,long conceptVersion) {}
    record ConceptSnapshot(UUID revisionId,long version,String title,String description) {}
    record SourceSnapshot(UUID sourceId,UUID revisionId,long version,String title,String url,String citation,String licenseNote) {}
    record Mapping(UUID mappingId,UUID spaceId,UUID conceptId,ConceptSnapshot concept,String system,String code,String systemVersion,String label,SourceSnapshot source,
                   String mappingState,String visibility,String state,long version,Instant createdAt,Instant updatedAt) {}
    record Revision(UUID revisionId,UUID mappingId,long version,String state,String mappingState,ConceptSnapshot concept,
                    String system,String code,String systemVersion,String label,SourceSnapshot source,UUID actorId,Instant occurredAt) {}
    record Page(List<Mapping> items,UUID nextAfter) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final UuidV7 ids;
    ExternalMappingStore(DataSource source,Clock clock) {
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);
    }
    static Edit edit(String system,String code,String systemVersion,String label,UUID source,long sourceVersion,long conceptVersion) {
        ConceptStore.id(source);if(sourceVersion<1||conceptVersion<1)throw bad("Positive inspected versions required");
        return new Edit(text(system,100,true),text(code,200,true),text(systemVersion,100,false),text(label,200,false),source,sourceVersion,conceptVersion);
    }
    private static String text(String value,int max,boolean required) {
        if(value==null)throw bad("Mapping text required");String clean=value.strip();
        if(clean.codePointCount(0,clean.length())>max||required&&clean.isEmpty()||clean.chars().anyMatch(c->c<32||c==127))throw bad("Invalid mapping text");
        for(int i=0;i<clean.length();i++){char c=clean.charAt(i);if(Character.isHighSurrogate(c)){if(++i==clean.length()||!Character.isLowSurrogate(clean.charAt(i)))throw bad("Invalid mapping text");}else if(Character.isLowSurrogate(c))throw bad("Invalid mapping text");}
        return clean;
    }
    Mapping create(UUID space,UUID concept,UUID actor,Edit input,String trace) {
        identify(space,concept);ConceptStore.id(actor);trace(trace);Edit e=validate(input);
        return transaction.execute(status->{
            UUID conceptRevision=lockConcept(space,concept,e.conceptVersion()),sourceRevision=lockSource(space,e);
            available(space,concept,e,null);UUID mapping=ids.next();Instant now=clock.instant();
            jdbc.update("INSERT INTO external_mapping(mapping_id,space_id,concept_id,concept_revision_id,external_system,external_code,system_version,external_label,source_id,source_revision_id,mapping_state,visibility,state,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,'CANDIDATE','PRIVATE','ACTIVE',1,?,?)",
                mapping,space,concept,conceptRevision,e.system(),e.code(),e.systemVersion(),e.label(),e.sourceId(),sourceRevision,Timestamp.from(now),Timestamp.from(now));
            Mapping result=load(space,concept,mapping,false);publish(result,actor,trace,"ExternalMappingCreated");return result;
        });
    }
    Mapping change(UUID space,UUID concept,UUID mapping,UUID actor,long expected,Edit input,boolean retire,String trace) {
        identify(space,concept);ConceptStore.id(mapping);ConceptStore.id(actor);trace(trace);if(expected<1)throw bad("Positive mapping version required");Edit e=retire?null:validate(input);
        return transaction.execute(status->{
            // Same Concept -> Source lock order as citations. Retained old pins are immutable.
            UUID conceptRevision=lockConcept(space,concept,retire?null:e.conceptVersion()),sourceRevision=retire?null:lockSource(space,e);
            Mapping prior=load(space,concept,mapping,true);if(prior==null||!"ACTIVE".equals(prior.state()))throw missing();
            if(prior.version()!=expected)throw stale();if(expected==Long.MAX_VALUE)throw new ResponseStatusException(HttpStatus.CONFLICT,"Mapping version limit reached");
            if(!retire)available(space,concept,e,mapping);
            jdbc.update("UPDATE external_mapping SET concept_revision_id=?,external_system=?,external_code=?,system_version=?,external_label=?,source_id=?,source_revision_id=?,state=?,version=version+1,updated_at=? WHERE mapping_id=?",
                retire?prior.concept().revisionId():conceptRevision,retire?prior.system():e.system(),retire?prior.code():e.code(),retire?prior.systemVersion():e.systemVersion(),retire?prior.label():e.label(),
                retire?prior.source().sourceId():e.sourceId(),retire?prior.source().revisionId():sourceRevision,retire?"RETIRED":"ACTIVE",Timestamp.from(clock.instant()),mapping);
            Mapping result=load(space,concept,mapping,false);publish(result,actor,trace,retire?"ExternalMappingRetired":"ExternalMappingUpdated");return result;
        });
    }
    Mapping get(UUID space,UUID concept,UUID mapping) {
        identify(space,concept);ConceptStore.id(mapping);
        var rows=jdbc.query(SELECT+" WHERE m.space_id=? AND m.concept_id=? AND m.mapping_id=? AND m.visibility='PRIVATE' AND m.state='ACTIVE' AND "+ACTIVE,(rs,n)->read(rs),space,concept,mapping);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    Page list(UUID space,UUID concept,UUID after,int limit) {
        identify(space,concept);if(after!=null)ConceptStore.id(after);limit(limit,100);
        if(jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND state='ACTIVE' AND visibility='PRIVATE'",UUID.class,space,concept).isEmpty())throw missing();
        var args=new ArrayList<Object>(List.of(space,concept));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(SELECT+" WHERE m.space_id=? AND m.concept_id=? AND m.visibility='PRIVATE' AND m.state='ACTIVE' AND "+ACTIVE+(after==null?"":" AND m.mapping_id>?")+" ORDER BY m.mapping_id LIMIT ?",(rs,n)->read(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,more?items.getLast().mappingId():null);
    }
    Page retired(UUID space,UUID concept,UUID after,int limit) {
        identify(space,concept);if(after!=null)ConceptStore.id(after);limit(limit,100);
        if(jdbc.queryForList("SELECT concept_id FROM concept WHERE space_id=? AND concept_id=? AND visibility='PRIVATE'",UUID.class,space,concept).isEmpty())throw missing();
        var args=new ArrayList<Object>(List.of(space,concept));if(after!=null)args.add(after);args.add(limit+1);
        var rows=jdbc.query(SELECT+" WHERE m.space_id=? AND m.concept_id=? AND m.visibility='PRIVATE' AND m.state='RETIRED'"+(after==null?"":" AND m.mapping_id>?")+" ORDER BY m.mapping_id LIMIT ?",(rs,n)->read(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,more?items.getLast().mappingId():null);
    }
    List<Revision> history(UUID space,UUID concept,UUID mapping,long before,int limit) {
        identify(space,concept);ConceptStore.id(mapping);limit(limit,10);if(before<1)throw bad("Invalid history cursor");if(load(space,concept,mapping,false)==null)throw missing();
        return jdbc.query("SELECT r.*,"+SNAPSHOTS+" FROM external_mapping_revision r JOIN concept_revision cr ON cr.concept_id=r.concept_id AND cr.revision_id=r.concept_revision_id JOIN source_revision sr ON sr.source_id=r.source_id AND sr.revision_id=r.source_revision_id WHERE r.mapping_id=? AND r.version<? ORDER BY r.version DESC LIMIT ?",
            (rs,n)->new Revision(rs.getObject("revision_id",UUID.class),rs.getObject("mapping_id",UUID.class),rs.getLong("version"),rs.getString("state"),rs.getString("mapping_state"),conceptSnapshot(rs),rs.getString("external_system"),rs.getString("external_code"),rs.getString("system_version"),rs.getString("external_label"),sourceSnapshot(rs),rs.getObject("actor_id",UUID.class),rs.getTimestamp("occurred_at").toInstant()),mapping,before,limit);
    }
    private UUID lockConcept(UUID space,UUID concept,Long expected) {
        var versions=jdbc.queryForList("SELECT version FROM concept WHERE space_id=? AND concept_id=? AND state='ACTIVE' AND visibility='PRIVATE' FOR UPDATE",Long.class,space,concept);
        if(versions.isEmpty())throw missing();long version=versions.getFirst();if(expected!=null&&version!=expected)throw stale();
        return jdbc.queryForObject("SELECT revision_id FROM concept_revision WHERE concept_id=? AND version=? AND state='ACTIVE'",UUID.class,concept,version);
    }
    private UUID lockSource(UUID space,Edit edit) {
        var versions=jdbc.queryForList("SELECT version FROM knowledge_source WHERE space_id=? AND source_id=? AND state='ACTIVE' AND visibility='PRIVATE' FOR UPDATE",Long.class,space,edit.sourceId());
        if(versions.isEmpty())throw missing();if(versions.getFirst()!=edit.sourceVersion())throw stale();
        return jdbc.queryForObject("SELECT revision_id FROM source_revision WHERE source_id=? AND version=? AND state='ACTIVE'",UUID.class,edit.sourceId(),edit.sourceVersion());
    }
    private void available(UUID space,UUID concept,Edit e,UUID exclude) {
        var args=new ArrayList<Object>(List.of(space,concept,e.system(),e.code(),e.systemVersion()));if(exclude!=null)args.add(exclude);
        if(!jdbc.queryForList("SELECT mapping_id FROM external_mapping WHERE space_id=? AND concept_id=? AND external_system=? AND external_code=? AND system_version=? AND state='ACTIVE'"+(exclude==null?"":" AND mapping_id<>?"),UUID.class,args.toArray()).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This external identifier and edition already map to the Concept");
    }
    private static final String SNAPSHOTS="cr.revision_id AS cr_id,cr.version AS cr_version,cr.title AS cr_title,cr.description AS cr_description,sr.source_id AS sr_source,sr.revision_id AS sr_id,sr.version AS sr_version,sr.title AS sr_title,sr.url AS sr_url,sr.citation AS sr_citation,sr.license_note AS sr_license";
    private static final String SELECT="SELECT m.*,"+SNAPSHOTS+" FROM external_mapping m JOIN concept_revision cr ON cr.concept_id=m.concept_id AND cr.revision_id=m.concept_revision_id JOIN source_revision sr ON sr.source_id=m.source_id AND sr.revision_id=m.source_revision_id";
    private static final String ACTIVE="EXISTS(SELECT 1 FROM concept c WHERE c.space_id=m.space_id AND c.concept_id=m.concept_id AND c.state='ACTIVE' AND c.visibility='PRIVATE')";
    private Mapping load(UUID space,UUID concept,UUID mapping,boolean lock) {
        var rows=jdbc.query(SELECT+" WHERE m.space_id=? AND m.concept_id=? AND m.mapping_id=? AND m.visibility='PRIVATE'"+(lock?" FOR UPDATE OF m":""),(rs,n)->read(rs),space,concept,mapping);return rows.isEmpty()?null:rows.getFirst();
    }
    private static ConceptSnapshot conceptSnapshot(ResultSet rs)throws SQLException{return new ConceptSnapshot(rs.getObject("cr_id",UUID.class),rs.getLong("cr_version"),rs.getString("cr_title"),rs.getString("cr_description"));}
    private static SourceSnapshot sourceSnapshot(ResultSet rs)throws SQLException{return new SourceSnapshot(rs.getObject("sr_source",UUID.class),rs.getObject("sr_id",UUID.class),rs.getLong("sr_version"),rs.getString("sr_title"),rs.getString("sr_url"),rs.getString("sr_citation"),rs.getString("sr_license"));}
    private static Mapping read(ResultSet rs)throws SQLException{return new Mapping(rs.getObject("mapping_id",UUID.class),rs.getObject("space_id",UUID.class),rs.getObject("concept_id",UUID.class),conceptSnapshot(rs),rs.getString("external_system"),rs.getString("external_code"),rs.getString("system_version"),rs.getString("external_label"),sourceSnapshot(rs),rs.getString("mapping_state"),rs.getString("visibility"),rs.getString("state"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());}
    private void publish(Mapping m,UUID actor,String trace,String type) {
        UUID revision=ids.next(),event=ids.next();Instant now=clock.instant();var c=m.concept();var s=m.source();
        jdbc.update("INSERT INTO external_mapping_revision(revision_id,mapping_id,version,state,mapping_state,concept_id,concept_revision_id,external_system,external_code,system_version,external_label,source_id,source_revision_id,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            revision,m.mappingId(),m.version(),m.state(),m.mappingState(),m.conceptId(),c.revisionId(),m.system(),m.code(),m.systemVersion(),m.label(),s.sourceId(),s.revisionId(),actor,Timestamp.from(now));
        var payload=ExternalMappingChangedPayloadV1.newBuilder().setMappingId(m.mappingId().toString()).setConceptId(m.conceptId().toString()).setConceptRevisionId(c.revisionId().toString()).setConceptVersion(c.version()).setConceptTitle(c.title()).setConceptDescription(c.description())
            .setSystem(m.system()).setCode(m.code()).setSystemVersion(m.systemVersion()).setLabel(m.label()).setSourceId(s.sourceId().toString()).setSourceRevisionId(s.revisionId().toString()).setSourceVersion(s.version()).setSourceTitle(s.title()).setSourceUrl(s.url()==null?"":s.url()).setSourceCitation(s.citation()).setSourceLicenseNote(s.licenseNote())
            .setRevisionId(revision.toString()).setVersion(m.version()).setState(m.state()).setVisibility(m.visibility()).setMappingState(m.mappingState()).build();
        var envelope=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("knowledge-service").setAggregateType("ExternalMapping").setAggregateId(m.mappingId().toString()).setSpaceId(m.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'external-mapping',?,?,?,?,'knowledge','external-mapping-events','1',?)",event,m.mappingId(),type,Timestamp.from(now),trace,envelope.toByteArray());
    }
    private static void identify(UUID space,UUID concept){ConceptStore.id(space);ConceptStore.id(concept);}
    private static Edit validate(Edit e){if(e==null)throw bad("Mapping required");return edit(e.system(),e.code(),e.systemVersion(),e.label(),e.sourceId(),e.sourceVersion(),e.conceptVersion());}
    private static void limit(int value,int maximum){if(value<1||value>maximum)throw bad("Invalid mapping page limit");}
    private static void trace(String value){if(value==null||!value.matches("[0-9a-f]{32}")||value.equals("0".repeat(32)))throw new IllegalArgumentException("Valid trace required");}
    private static ResponseStatusException stale(){return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Mapping, Concept or Source changed; inspect before saving");}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Private Concept mapping or reference unavailable");}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
