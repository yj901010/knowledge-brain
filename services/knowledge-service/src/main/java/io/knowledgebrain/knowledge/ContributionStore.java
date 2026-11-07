package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.contribution.v1.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ContributionStore {
    record Proposal(UUID contributionId,UUID spaceId,String operation,UUID conceptId,long baseVersion,String title,String description,String rationale,UUID authorId,String state,long version,Instant createdAt,Instant updatedAt,Long mergedConceptVersion) {}
    record Review(UUID reviewId,UUID reviewerId,String decision,String reason,long contributionVersion,Instant occurredAt) {}
    record Summary(UUID contributionId,UUID conceptId,String operation,String title,UUID authorId,String state,long version,long baseVersion) {}
    record Page(List<Summary> items,UUID nextAfter) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ConceptStore concepts;private final Clock clock;private final UuidV7 ids;
    ContributionStore(DataSource source,ConceptStore concepts,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.concepts=concepts;this.clock=clock;ids=new UuidV7(clock);}
    Proposal submit(UUID space,UUID actor,UUID target,long base,String title,String description,String rationale,String trace){
        ConceptStore.id(space);ConceptStore.id(actor);ConceptStore.text(title,description);reason(rationale,true);trace(trace);
        if(target==null&&base!=0||target!=null&&base<1)throw error(HttpStatus.BAD_REQUEST,"Invalid canonical base version");
        if(target!=null)ConceptStore.id(target);
        return tx.execute(status->{
            UUID id=ids.next(),concept=target==null?ids.next():target;Instant now=clock.instant();
            if(target!=null){var prior=concepts.get(space,target);if(!"PUBLIC".equals(prior.visibility()))throw error(HttpStatus.NOT_FOUND,"Public Concept unavailable");if(prior.version()!=base)throw error(HttpStatus.PRECONDITION_FAILED,"Canonical Concept changed");}
            jdbc.update("INSERT INTO contribution(contribution_id,space_id,operation,concept_id,base_version,title,description,rationale,author_id,state,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,'SUBMITTED',1,?,?)",id,space,target==null?"CREATE":"UPDATE",concept,base,title,description,rationale,actor,Timestamp.from(now),Timestamp.from(now));
            var proposal=get(space,id,false);event(proposal,actor,trace,null,"");return proposal;
        });
    }
    Proposal get(UUID space,UUID id){return get(space,id,false);}
    private Proposal get(UUID space,UUID id,boolean lock){
        var rows=jdbc.query("SELECT * FROM contribution WHERE space_id=? AND contribution_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Proposal(r.getObject("contribution_id",UUID.class),r.getObject("space_id",UUID.class),r.getString("operation"),r.getObject("concept_id",UUID.class),r.getLong("base_version"),r.getString("title"),r.getString("description"),r.getString("rationale"),r.getObject("author_id",UUID.class),r.getString("state"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),r.getObject("merged_concept_version",Long.class)),space,id);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Contribution unavailable");return rows.getFirst();
    }
    Page list(UUID space,UUID after,int limit){
        var rows=jdbc.query("SELECT contribution_id,concept_id,operation,title,author_id,state,version,base_version FROM contribution WHERE space_id=?"+(after==null?"":" AND contribution_id>?")+" ORDER BY contribution_id LIMIT ?",(r,n)->new Summary(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getObject(5,UUID.class),r.getString(6),r.getLong(7),r.getLong(8)),after==null?new Object[]{space,limit+1}:new Object[]{space,after,limit+1});
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(items,rows.size()>limit?items.getLast().contributionId():null);
    }
    List<Review> reviews(UUID space,UUID id){get(space,id);return jdbc.query("SELECT review_id,reviewer_id,decision,reason,contribution_version,occurred_at FROM contribution_review WHERE contribution_id=? ORDER BY contribution_version",(r,n)->new Review(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getLong(5),r.getTimestamp(6).toInstant()),id);}
    Proposal decide(UUID space,UUID id,UUID actor,long expected,String decision,String reason,String trace){
        if(!Set.of("APPROVED","REJECTED").contains(decision))throw error(HttpStatus.BAD_REQUEST,"Approve or reject required");reason(reason,true);
        return transition(space,id,actor,expected,decision,reason,trace);
    }
    Proposal merge(UUID space,UUID id,UUID actor,long expected,String trace){return transition(space,id,actor,expected,"MERGED","",trace);}
    private Proposal transition(UUID space,UUID id,UUID actor,long expected,String state,String reason,String trace){
        ConceptStore.id(space);ConceptStore.id(id);ConceptStore.id(actor);trace(trace);
        return tx.execute(status->{
            var prior=get(space,id,true);
            if(actor.equals(prior.authorId()))throw error(HttpStatus.FORBIDDEN,"Self-review and self-merge forbidden");
            if(prior.version()!=expected)throw error(HttpStatus.PRECONDITION_FAILED,"Contribution changed; reload");
            if(!("MERGED".equals(state)?"APPROVED":"SUBMITTED").equals(prior.state()))throw error(HttpStatus.CONFLICT,"Invalid contribution transition");
            Long canonical=null;
            if("MERGED".equals(state)){
                var concept="CREATE".equals(prior.operation())?concepts.createWithId(space,prior.conceptId(),actor,prior.title(),prior.description(),trace,"PUBLIC"):concepts.changeScoped(space,prior.conceptId(),actor,prior.baseVersion(),prior.title(),prior.description(),false,trace,"PUBLIC");
                canonical=concept.version();
            }
            var now=clock.instant();UUID review=ids.next();
            jdbc.update("UPDATE contribution SET state=?,version=version+1,updated_at=?,merged_concept_version=? WHERE contribution_id=?",state,Timestamp.from(now),canonical,id);
            jdbc.update("INSERT INTO contribution_review(review_id,contribution_id,contribution_version,reviewer_id,decision,reason,occurred_at) VALUES (?,?,?,?,?,?,?)",review,id,prior.version()+1,actor,state,reason,Timestamp.from(now));
            var current=get(space,id,false);event(current,actor,trace,review,reason);return current;
        });
    }
    private void event(Proposal p,UUID actor,String trace,UUID review,String reason){
        UUID event=ids.next();Instant now=clock.instant();
        var payload=ContributionChangedPayloadV1.newBuilder().setContributionId(p.contributionId().toString()).setConceptId(p.conceptId().toString()).setOperation(p.operation()).setBaseVersion(p.baseVersion()).setVersion(p.version()).setState(p.state()).setAuthorId(p.authorId().toString()).setTitle(p.title()).setDescription(p.description()).setRationale(p.rationale()).setReviewReason(reason).setReviewId(review==null?"":review.toString()).setMergedConceptVersion(p.mergedConceptVersion()==null?0:p.mergedConceptVersion()).build();
        String type="Contribution"+switch(p.state()){case "SUBMITTED"->"Submitted";case "APPROVED"->"Approved";case "REJECTED"->"Rejected";case "MERGED"->"Merged";default->throw new IllegalStateException();};
        var envelope=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("knowledge-service").setAggregateType("Contribution").setAggregateId(p.contributionId().toString()).setSpaceId(p.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'contribution',?,?,?,?,'knowledge','contribution-events','1',?)",event,p.contributionId(),type,Timestamp.from(now),trace,envelope.toByteArray());
    }
    private static void reason(String value,boolean required){if(value==null||value.indexOf(0)>=0||value.codePointCount(0,value.length())>2000||required&&value.isBlank())throw error(HttpStatus.BAD_REQUEST,"Reason required; maximum 2000 characters");}
    private static void trace(String trace){if(trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw new IllegalArgumentException("Trace required");}
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
