package io.knowledgebrain.knowledge;

import com.google.protobuf.ByteString;
import io.knowledgebrain.contracts.knowledge.publicrecord.v1.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reviewed public facts. Private working rows are never publication inputs. */
final class PublicRecordStore {
    static final Set<String> KINDS=Set.of("ALIAS","CLAIM","SOURCE","EVIDENCE","MAPPING","VERIFICATION","QUALIFIER");
    record Fact(UUID recordId,UUID spaceId,String kind,String title,JsonNode content,String state,long version,Instant createdAt,Instant updatedAt,JsonNode qualifierPins) {}
    record Proposal(UUID proposalId,UUID spaceId,UUID recordId,String kind,String operation,long baseVersion,String title,JsonNode content,String rationale,UUID authorId,String state,long version,Long mergedVersion,Instant createdAt,Instant updatedAt,JsonNode qualifierPins) {}
    record Summary(UUID proposalId,UUID recordId,String kind,String operation,String title,UUID authorId,String state,long version,long baseVersion) {}
    record Review(UUID reviewId,long version,UUID reviewerId,String decision,String reason,Instant occurredAt) {}
    record Revision(UUID revisionId,long version,String title,JsonNode content,String state,UUID actorId,Instant occurredAt,JsonNode qualifierPins) {}
    record Page<T>(List<T> items,UUID nextAfter) {}
    record Card(UUID recordId,String kind,String title,String state,long version) {}
    record ConceptCard(UUID conceptId,String title,long version) {}
    record Relations(List<ConceptCard> concepts,List<Fact> claims,boolean truncated) {}
    record Verification(String effectiveLevel,boolean needsReview,Fact assessment) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;
    private final JsonMapper json=JsonMapper.builder().build();
    PublicRecordStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
    Fact get(UUID space,UUID id){ConceptStore.id(space);ConceptStore.id(id);return fact(space,id,false);}
    private Fact fact(UUID space,UUID id,boolean lock){
        var rows=jdbc.query("SELECT * FROM public_knowledge_record WHERE space_id=? AND record_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Fact(r.getObject("record_id",UUID.class),space,r.getString("kind"),r.getString("title"),json.readTree(r.getString("content")),r.getString("state"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),json.readTree(r.getString("qualifier_pins"))),space,id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    Proposal proposal(UUID space,UUID id){return proposal(space,id,false);}
    private Proposal proposal(UUID space,UUID id,boolean lock){
        var rows=jdbc.query("SELECT * FROM public_knowledge_proposal WHERE space_id=? AND proposal_id=?"+(lock?" FOR UPDATE":""),(r,n)->new Proposal(id,space,r.getObject("record_id",UUID.class),r.getString("kind"),r.getString("operation"),r.getLong("base_version"),r.getString("title"),json.readTree(r.getString("content")),r.getString("rationale"),r.getObject("author_id",UUID.class),r.getString("state"),r.getLong("version"),r.getObject("merged_version",Long.class),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),json.readTree(r.getString("qualifier_pins"))),space,id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    Page<Card> list(UUID space,String kind,String state,UUID after,int limit,String q){
        paging(after,limit);if(kind!=null&&!KINDS.contains(kind)||!Set.of("ACTIVE","RETIRED").contains(state))throw bad("Invalid public catalog filter");
        q=text(q==null?"":q,64,false);var args=new ArrayList<Object>(List.of(space,state));String where="space_id=? AND state=?";
        if(kind!=null){where+=" AND kind=?";args.add(kind);}if(after!=null){where+=" AND record_id>?";args.add(after);}
        // Position is literal matching, never a LIKE wildcard or a user SQL expression.
        if(!q.isEmpty()){where+=" AND (position(lower(?) in lower(title))>0 OR (kind='ALIAS' AND position(lower(?) in lower(content->>'label'))>0))";args.add(q);args.add(q);}
        args.add(limit+1);var rows=jdbc.query("SELECT record_id FROM public_knowledge_record WHERE "+where+" ORDER BY record_id LIMIT ?",(r,n)->r.getObject(1,UUID.class),args.toArray());
        var facts=rows.stream().limit(limit).map(id->get(space,id)).map(v->new Card(v.recordId(),v.kind(),v.title(),v.state(),v.version())).toList();return new Page<>(facts,rows.size()>limit?facts.getLast().recordId():null);
    }
    Page<ConceptCard> explore(UUID space,String q,UUID after,int limit){
        paging(after,limit);q=text(q,64,false);var args=new ArrayList<Object>(List.of(space,q,q));
        String sql="SELECT c.concept_id,c.title,c.version FROM concept c WHERE c.space_id=? AND c.visibility='PUBLIC' AND c.state='ACTIVE' AND (position(lower(?) in lower(c.title))>0 OR EXISTS(SELECT 1 FROM public_knowledge_record a WHERE a.space_id=c.space_id AND a.kind='ALIAS' AND a.state='ACTIVE' AND a.content->>'conceptId'=c.concept_id::text AND position(lower(?) in lower(a.content->>'label'))>0))";
        if(after!=null){sql+=" AND c.concept_id>?";args.add(after);}args.add(limit+1);
        var rows=jdbc.query(sql+" ORDER BY c.concept_id LIMIT ?",(r,n)->new ConceptCard(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)),args.toArray());var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page<>(items,rows.size()>limit?items.getLast().conceptId():null);
    }
    Relations relations(UUID space,UUID concept,int limit){
        ConceptStore.id(concept);if(limit<1||limit>20)throw bad("Relation limit must be1..20");
        var center=jdbc.query("SELECT concept_id,title,version FROM concept WHERE space_id=? AND concept_id=? AND visibility='PUBLIC' AND state='ACTIVE'",(r,n)->new ConceptCard(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)),space,concept);if(center.isEmpty())throw missing();
        var rows=jdbc.queryForList("SELECT record_id FROM public_knowledge_record WHERE space_id=? AND kind='CLAIM' AND state='ACTIVE' AND (content->>'subjectId'=? OR (content->>'objectType'='CONCEPT' AND content->>'objectValue'=?)) ORDER BY record_id LIMIT ?",UUID.class,space,concept.toString(),concept.toString(),limit+1);
        var claims=rows.stream().limit(limit).map(id->get(space,id)).toList();var selected=new TreeSet<UUID>();selected.add(concept);
        for(var claim:claims){selected.add(id(claim.content(),"subjectId"));selected.add(id(claim.content(),"predicateId"));if(str(claim.content(),"objectType").equals("CONCEPT"))selected.add(id(claim.content(),"objectValue"));}
        var nodes=new ArrayList<ConceptCard>();for(UUID id:selected)nodes.addAll(jdbc.query("SELECT concept_id,title,version FROM concept WHERE space_id=? AND concept_id=? AND visibility='PUBLIC' AND state='ACTIVE'",(r,n)->new ConceptCard(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)),space,id));
        return new Relations(List.copyOf(nodes),claims,rows.size()>limit);
    }
    Verification verification(UUID space,UUID claim){
        Fact current=get(space,claim);if(!current.kind().equals("CLAIM"))throw bad("Claim required");
        if(!current.state().equals("ACTIVE"))return new Verification("DRAFT",true,null);
        var rows=jdbc.queryForList("SELECT record_id FROM public_knowledge_record WHERE space_id=? AND kind='VERIFICATION' AND state='ACTIVE' AND content->>'claimId'=? AND content->>'claimVersion'=? ORDER BY record_id DESC LIMIT 1",UUID.class,space,claim.toString(),Long.toString(current.version()));
        if(rows.isEmpty())return new Verification("DRAFT",false,null);Fact assessment=get(space,rows.getFirst());boolean valid=currentContext(space,current);
        try{var evidence=reference(space,assessment.content(),"evidenceId","evidenceVersion","EVIDENCE");reference(space,evidence.content(),"sourceId","sourceVersion","SOURCE");}catch(ResponseStatusException e){valid=false;}
        valid=valid&&samePins(assessment.qualifierPins(),qualifierPins(space,claim,current.version()))&&qualifierContext(space,claim,current.version());
        return new Verification(valid?str(assessment.content(),"level"):"DRAFT",!valid,assessment);
    }
    Page<Summary> queue(UUID space,String state,String kind,UUID author,UUID after,int limit){
        paging(after,limit);if(state!=null&&!Set.of("SUBMITTED","APPROVED","REJECTED","MERGED").contains(state)||kind!=null&&!KINDS.contains(kind))throw bad("Invalid queue filter");
        var args=new ArrayList<Object>(List.of(space));String where="space_id=?";
        if(state!=null){where+=" AND state=?";args.add(state);}if(kind!=null){where+=" AND kind=?";args.add(kind);}if(author!=null){where+=" AND author_id=?";args.add(author);}if(after!=null){where+=" AND proposal_id>?";args.add(after);}args.add(limit+1);
        var rows=jdbc.query("SELECT proposal_id,record_id,kind,operation,title,author_id,state,version,base_version FROM public_knowledge_proposal WHERE "+where+" ORDER BY proposal_id LIMIT ?",(r,n)->new Summary(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getObject(6,UUID.class),r.getString(7),r.getLong(8),r.getLong(9)),args.toArray());
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page<>(items,rows.size()>limit?items.getLast().proposalId():null);
    }
    List<Review> reviews(UUID space,UUID id){proposal(space,id);return jdbc.query("SELECT * FROM public_knowledge_review WHERE proposal_id=? ORDER BY version",(r,n)->new Review(r.getObject("review_id",UUID.class),r.getLong("version"),r.getObject("reviewer_id",UUID.class),r.getString("decision"),r.getString("reason"),r.getTimestamp("occurred_at").toInstant()),id);}
    List<Revision> revisions(UUID space,UUID id,long before,int limit){get(space,id);if(before<1||limit<1||limit>10)throw bad("Invalid history page");return jdbc.query("SELECT * FROM public_knowledge_revision WHERE record_id=? AND version<? ORDER BY version DESC LIMIT ?",(r,n)->new Revision(r.getObject("revision_id",UUID.class),r.getLong("version"),r.getString("title"),json.readTree(r.getString("content")),r.getString("state"),r.getObject("actor_id",UUID.class),r.getTimestamp("occurred_at").toInstant(),json.readTree(r.getString("qualifier_pins"))),id,before,limit);}

    Proposal submit(UUID space,UUID actor,String kind,String operation,UUID target,long base,String title,JsonNode input,String rationale,String trace){return submit(space,actor,kind,operation,target,base,title,input,rationale,trace,json.createObjectNode());}
    Proposal submit(UUID space,UUID actor,String kind,String operation,UUID target,long base,String title,JsonNode input,String rationale,String trace,JsonNode qualifierPins){
        ConceptStore.id(space);ConceptStore.id(actor);trace(trace);title=text(title,200,true);rationale=text(rationale,2000,true);
        if(!KINDS.contains(kind)||!Set.of("CREATE","UPDATE","RETIRE").contains(operation)||("CREATE".equals(operation)?target!=null||base!=0:target==null||base<1))throw bad("Invalid proposal target");
        final String caption=title,reason=rationale;
        return tx.execute(status->{
            lock(space);UUID record=target==null?ids.next():target;
            Fact prior=target==null?null:fact(space,target,false);
            if(prior!=null&&(!prior.kind().equals(kind)||!prior.state().equals("ACTIVE")))throw missing();
            if(prior!=null&&prior.version()!=base)throw stale();
            JsonNode content="RETIRE".equals(operation)?prior.content():validate(space,kind,input);
            if(!"RETIRE".equals(operation))policy(space,kind,content,prior);
            JsonNode pins="RETIRE".equals(operation)?prior.qualifierPins():pins(space,kind,content,qualifierPins);
            if(kind.equals("VERIFICATION")&&prior!=null&&!samePins(prior.qualifierPins(),pins)&&!Set.of("DRAFT","SOURCED").contains(str(content,"level")))throw bad("Changed qualifier scope must be sourced anew");
            UUID id=ids.next();Instant now=clock.instant();
            jdbc.update("INSERT INTO public_knowledge_proposal(proposal_id,space_id,record_id,kind,operation,base_version,title,content,rationale,author_id,state,version,created_at,updated_at,qualifier_pins) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,'SUBMITTED',1,?,?,?::jsonb)",id,space,record,kind,operation,base,caption,content.toString(),reason,actor,Timestamp.from(now),Timestamp.from(now),pins.toString());
            var result=proposal(space,id);event(result,actor,trace,null,"PublicProposalSubmitted",reason);return result;
        });
    }
    Proposal decide(UUID space,UUID id,UUID actor,long expected,String decision,String reason,String trace){
        if(!Set.of("APPROVED","REJECTED","MERGED").contains(decision))throw bad("Invalid review decision");reason=text(reason,2000,true);trace(trace);ConceptStore.id(actor);final String explanation=reason;
        return tx.execute(status->{
            lock(space);var prior=proposal(space,id,true);
            if(prior.authorId().equals(actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Self-review and self-merge forbidden");
            if(prior.version()!=expected)throw stale();
            if(!prior.state().equals("MERGED".equals(decision)?"APPROVED":"SUBMITTED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid proposal transition");
            Long merged=null;
            if("MERGED".equals(decision))merged=merge(prior,actor,trace);
            Instant now=clock.instant();jdbc.update("UPDATE public_knowledge_proposal SET state=?,version=version+1,merged_version=?,updated_at=? WHERE proposal_id=?",decision,merged,Timestamp.from(now),id);
            jdbc.update("INSERT INTO public_knowledge_review VALUES (?,?,?,?,?,?,?)",ids.next(),id,prior.version()+1,actor,decision,explanation,Timestamp.from(now));
            var result=proposal(space,id);event(result,actor,trace,null,"PublicProposal"+switch(decision){case "APPROVED"->"Approved";case "REJECTED"->"Rejected";default->"Merged";},explanation);return result;
        });
    }
    private long merge(Proposal p,UUID actor,String trace){
        Fact prior="CREATE".equals(p.operation())?null:fact(p.spaceId(),p.recordId(),true);
        if(prior!=null&&(prior.version()!=p.baseVersion()||!prior.state().equals("ACTIVE")))throw stale();
        boolean retire="RETIRE".equals(p.operation());JsonNode content=retire?prior.content():validate(p.spaceId(),p.kind(),p.content());
        if(!retire){policy(p.spaceId(),p.kind(),content,prior);pins(p.spaceId(),p.kind(),content,p.qualifierPins());}
        if(!retire&&p.kind().equals("QUALIFIER")&&jdbc.queryForObject("SELECT count(*) FROM public_knowledge_record WHERE space_id=? AND kind='QUALIFIER' AND content->>'claimId'=? AND content->>'claimVersion'=? AND record_id<>?",Integer.class,p.spaceId(),str(content,"claimId"),Long.toString(number(content,"claimVersion",false)),p.recordId())>=100)throw new ResponseStatusException(HttpStatus.CONFLICT,"At most100 qualifier identities per Claim revision");
        long version=prior==null?1:prior.version()+1;if(version<1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Version limit reached");
        String state=retire?"RETIRED":"ACTIVE",key=identity(p.kind(),content,p.recordId());Instant now=clock.instant();
        if(!retire&&jdbc.queryForObject("SELECT count(*) FROM public_knowledge_record WHERE space_id=? AND kind=? AND identity_key=? AND state='ACTIVE' AND record_id<>?",Integer.class,p.spaceId(),p.kind(),key,p.recordId())>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Equivalent public fact already active");
        if(prior==null)jdbc.update("INSERT INTO public_knowledge_record(record_id,space_id,kind,identity_key,title,content,state,version,created_at,updated_at,qualifier_pins) VALUES (?,?,?,?,?,?::jsonb,?,1,?,?,?::jsonb)",p.recordId(),p.spaceId(),p.kind(),key,p.title(),content.toString(),state,Timestamp.from(now),Timestamp.from(now),p.qualifierPins().toString());
        else jdbc.update("UPDATE public_knowledge_record SET identity_key=?,title=?,content=?::jsonb,state=?,version=?,updated_at=?,qualifier_pins=?::jsonb WHERE record_id=?",key,p.title(),content.toString(),state,version,Timestamp.from(now),p.qualifierPins().toString(),p.recordId());
        UUID revision=ids.next();jdbc.update("INSERT INTO public_knowledge_revision VALUES (?,?,?,?,?::jsonb,?,?,?,?::jsonb)",revision,p.recordId(),version,p.title(),content.toString(),state,actor,Timestamp.from(now),p.qualifierPins().toString());
        event(new Proposal(p.proposalId(),p.spaceId(),p.recordId(),p.kind(),p.operation(),p.baseVersion(),p.title(),content,p.rationale(),p.authorId(),state,version,version,p.createdAt(),now,p.qualifierPins()),actor,trace,revision,"PublicRecordChanged","");
        PublicKnowledgeEvents.publish(jdbc,ids,clock,revision,p.recordId(),p.spaceId(),actor,trace,p.kind(),version,state,p.title(),content,p.qualifierPins());return version;
    }
    // Serialize public record writes per Space without locking or reading another
    // service's tables. Dependencies are validated again in the merge transaction.
    private void lock(UUID space){ConceptStore.id(space);jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,space.getMostSignificantBits()^space.getLeastSignificantBits());}
    private JsonNode validate(UUID space,String kind,JsonNode node){
        if(node==null||!node.isObject()||node.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>12000)throw bad("Invalid public fact");
        var v=node.deepCopy();switch(kind){
            case "ALIAS"->{fields(v,"conceptId","conceptVersion","label","scope","scopeValue");concept(space,v,"conceptId","conceptVersion");put(v,"label",AliasStore.label(str(v,"label")));String scope=str(v,"scope"),value=text(str(v,"scopeValue"),200,false);if(!Set.of("GLOBAL","COUNTRY","ORGANIZATION").contains(scope)||scope.equals("GLOBAL")&&!value.isEmpty()||scope.equals("COUNTRY")&&!value.matches("[A-Z]{2}")||scope.equals("ORGANIZATION")&&value.isEmpty())throw bad("Explicit Alias scope required");put(v,"scopeValue",value);}
            case "CLAIM"->{fields(v,"subjectId","subjectVersion","predicateId","predicateVersion","objectType","objectValue","objectVersion");concept(space,v,"subjectId","subjectVersion");concept(space,v,"predicateId","predicateVersion");var object=KnowledgeValue.value(str(v,"objectType"),str(v,"objectValue"));put(v,"objectValue",object.value());if(object.type().equals("CONCEPT"))concept(space,v,"objectValue","objectVersion");else if(number(v,"objectVersion",true)!=0)throw bad("Literal object version must be zero");}
            case "SOURCE"->{fields(v,"title","url","citation","licenseNote");var f=SourceStore.fields(str(v,"title"),str(v,"url"),str(v,"citation"),text(str(v,"licenseNote"),1000,true));put(v,"title",f.title());put(v,"url",f.url()==null?"":f.url());}
            case "EVIDENCE"->{fields(v,"claimId","claimVersion","sourceId","sourceVersion","locator","explanation");reference(space,v,"claimId","claimVersion","CLAIM");reference(space,v,"sourceId","sourceVersion","SOURCE");put(v,"locator",text(str(v,"locator"),500,true));put(v,"explanation",text(str(v,"explanation"),1000,true));}
            case "QUALIFIER"->{fields(v,"claimId","claimVersion","predicateId","predicateVersion","valueType","value","valueVersion");reference(space,v,"claimId","claimVersion","CLAIM");concept(space,v,"predicateId","predicateVersion");var value=KnowledgeValue.value(str(v,"valueType"),str(v,"value"));put(v,"value",value.value());if(value.type().equals("CONCEPT"))concept(space,v,"value","valueVersion");else if(number(v,"valueVersion",true)!=0)throw bad("Literal qualifier version must be zero");}
            case "MAPPING"->{fields(v,"conceptId","conceptVersion","sourceId","sourceVersion","system","code","systemVersion","label","mappingState","authority");concept(space,v,"conceptId","conceptVersion");reference(space,v,"sourceId","sourceVersion","SOURCE");for(String field:List.of("system","code","systemVersion","label","authority"))put(v,field,text(str(v,field),field.equals("authority")?1000:200,Set.of("system","code").contains(field)));if(!Set.of("CANDIDATE","CONFIRMED","OFFICIAL").contains(str(v,"mappingState")))throw bad("Invalid mapping state");if(!str(v,"mappingState").equals("CANDIDATE")&&(str(v,"systemVersion").isBlank()||str(v,"authority").isBlank()))throw bad("Confirmed mapping requires edition and provenance");}
            case "VERIFICATION"->{fields(v,"claimId","claimVersion","evidenceId","evidenceVersion","level","reason");reference(space,v,"claimId","claimVersion","CLAIM");var e=reference(space,v,"evidenceId","evidenceVersion","EVIDENCE");if(!e.content().path("claimId").equals(v.path("claimId"))||number(e.content(),"claimVersion",false)!=number(v,"claimVersion",false))throw bad("Evidence is for another Claim revision");reference(space,e.content(),"sourceId","sourceVersion","SOURCE");if(!Set.of("DRAFT","SOURCED","COMMUNITY_REVIEWED","EXPERT_VERIFIED").contains(str(v,"level")))throw bad("Invalid verification level");put(v,"reason",text(str(v,"reason"),1000,true));}
            default->throw bad("Invalid public record kind");
        }return v;
    }
    private void policy(UUID space,String kind,JsonNode content,Fact prior){
        if(prior!=null&&kind.equals("QUALIFIER")&&number(prior.content(),"claimVersion",false)!=number(content,"claimVersion",false))throw bad("Qualifier Claim revision is immutable");
        if(prior!=null){String owner=switch(kind){case "ALIAS","MAPPING"->"conceptId";case "EVIDENCE","VERIFICATION","QUALIFIER"->"claimId";case "CLAIM"->"subjectId";default->null;};if(owner!=null&&!prior.content().path(owner).equals(content.path(owner)))throw bad("The owning target cannot be changed");}
        if(kind.equals("MAPPING")){String old=prior==null?"NONE":str(prior.content(),"mappingState"),next=str(content,"mappingState");if(prior==null&&!next.equals("CANDIDATE")||next.equals("OFFICIAL")&&!Set.of("CONFIRMED","OFFICIAL").contains(old))throw bad("Mapping must progress through candidate and confirmed");}
        if(kind.equals("VERIFICATION")){var levels=List.of("DRAFT","SOURCED","COMMUNITY_REVIEWED","EXPERT_VERIFIED");int old=prior==null?0:levels.indexOf(str(prior.content(),"level")),next=levels.indexOf(str(content,"level"));if(next>old+1)throw bad("Verification progression cannot skip a stage");if(prior!=null&&number(prior.content(),"claimVersion",false)!=number(content,"claimVersion",false)&&next>1)throw bad("Changed Claim revision must be sourced anew");}
    }
    private void concept(UUID space,JsonNode node,String id,String version){UUID target=id(node,id);long expected=number(node,version,false);var rows=jdbc.queryForList("SELECT version FROM concept WHERE space_id=? AND concept_id=? AND visibility='PUBLIC' AND state='ACTIVE' FOR SHARE",Long.class,space,target);if(rows.isEmpty())throw missing();if(rows.getFirst()!=expected)throw stale();}
    private Fact reference(UUID space,JsonNode node,String id,String version,String kind){var value=fact(space,id(node,id),false);if(!value.kind().equals(kind)||!value.state().equals("ACTIVE"))throw missing();if(value.version()!=number(node,version,false))throw stale();return value;}
    String authority(Proposal p){if(p.kind().equals("VERIFICATION")&&str(p.content(),"level").equals("EXPERT_VERIFIED"))return "CLAIM_EXPERT_VERIFY";if(p.kind().equals("MAPPING")&&str(p.content(),"mappingState").equals("OFFICIAL"))return "MAPPING_OFFICIAL_REVIEW";return null;}
    private String identity(String kind,JsonNode content,UUID id){
        // Canonical tuples prevent delimiter collisions and bound Unicode index width.
        List<?> tuple=switch(kind){case "ALIAS"->List.of(str(content,"conceptId"),str(content,"scope"),str(content,"scopeValue"),str(content,"label"));case "CLAIM"->List.of(str(content,"subjectId"),str(content,"predicateId"),str(content,"objectType"),str(content,"objectValue"));case "MAPPING"->List.of(str(content,"conceptId"),str(content,"system"),str(content,"code"),str(content,"systemVersion"));case "VERIFICATION"->List.of(str(content,"claimId"),number(content,"claimVersion",false));case "QUALIFIER"->List.of(str(content,"claimId"),number(content,"claimVersion",false),str(content,"predicateId"),str(content,"valueType"),str(content,"value"));default->List.of(id.toString());};
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(tuple)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void event(Proposal p,UUID actor,String trace,UUID revision,String type,String reason){
        if(p.kind().equals("QUALIFIER")){if(revision==null)PublicKnowledgeEvents.proposal(jdbc,ids,clock,p,actor,trace);return;}
        UUID event=ids.next();Instant now=clock.instant();var payload=PublicRecordChangedPayloadV1.newBuilder().setRecordId(p.recordId().toString()).setKind(p.kind()).setVersion(p.version()).setState(p.state()).setTitle(p.title()).setContentJson(p.content().toString()).setRevisionId(revision==null?"":revision.toString()).setProposalId(p.proposalId().toString()).setOperation(p.operation()).setAuthorId(p.authorId().toString()).setBaseVersion(p.baseVersion()).setMergedVersion(p.mergedVersion()==null?0:p.mergedVersion()).setReason(reason).build();
        UUID aggregate=revision==null?p.proposalId():p.recordId();var envelope=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("knowledge-service").setAggregateType(revision==null?"PublicProposal":"PublicRecord").setAggregateId(aggregate.toString()).setSpaceId(p.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(ByteString.copyFrom(payload.toByteArray())).build();
        jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'public-record',?,?,?,?,'knowledge','public-record-events','1',?)",event,aggregate,type,Timestamp.from(now),trace,envelope.toByteArray());
    }
    boolean currentContext(UUID space,Fact fact){return currentContext(space,fact,0);}
    private boolean currentContext(UUID space,Fact fact,int depth){if(depth>4||!fact.state().equals("ACTIVE"))return false;try{validate(space,fact.kind(),fact.content());if(fact.kind().equals("VERIFICATION")&&(!samePins(fact.qualifierPins(),qualifierPins(space,id(fact.content(),"claimId"),number(fact.content(),"claimVersion",false)))||!qualifierContext(space,id(fact.content(),"claimId"),number(fact.content(),"claimVersion",false))))return false;for(String field:List.of("claimId","sourceId","evidenceId"))if(fact.content().has(field)&&!currentContext(space,get(space,id(fact.content(),field)),depth+1))return false;return true;}catch(ResponseStatusException e){return false;}}
    static boolean samePins(JsonNode a,JsonNode b){if(a==null||b==null||!a.isObject()||!b.isObject()||a.size()!=b.size())return false;for(var entry:a.properties()){var other=b.path(entry.getKey());if(!entry.getValue().isIntegralNumber()||!entry.getValue().canConvertToLong()||!other.isIntegralNumber()||!other.canConvertToLong()||entry.getValue().asLong()<1||entry.getValue().asLong()!=other.asLong())return false;}return true;}
    boolean qualifierContext(UUID space,UUID claim,long version){return jdbc.queryForObject("SELECT count(*) FROM public_knowledge_record q LEFT JOIN concept p ON p.concept_id=(q.content->>'predicateId')::uuid LEFT JOIN concept o ON o.concept_id=CASE WHEN q.content->>'valueType'='CONCEPT' THEN (q.content->>'value')::uuid ELSE NULL END WHERE q.space_id=? AND q.kind='QUALIFIER' AND q.state='ACTIVE' AND q.content->>'claimId'=? AND q.content->>'claimVersion'=? AND (p.concept_id IS NULL OR p.space_id<>q.space_id OR p.visibility<>'PUBLIC' OR p.state<>'ACTIVE' OR p.version<>(q.content->>'predicateVersion')::bigint OR (q.content->>'valueType'='CONCEPT' AND (o.concept_id IS NULL OR o.space_id<>q.space_id OR o.visibility<>'PUBLIC' OR o.state<>'ACTIVE' OR o.version<>(q.content->>'valueVersion')::bigint)))",Integer.class,space,claim.toString(),Long.toString(version))==0;}
    JsonNode qualifierPins(UUID space,UUID claim,long version){var result=json.createObjectNode();jdbc.query("SELECT record_id,version FROM public_knowledge_record WHERE space_id=? AND kind='QUALIFIER' AND content->>'claimId'=? AND content->>'claimVersion'=? ORDER BY record_id",(r,n)->{result.put(r.getObject(1,UUID.class).toString(),r.getLong(2));return 0;},space,claim.toString(),Long.toString(version));return result;}
    private JsonNode pins(UUID space,String kind,JsonNode content,JsonNode input){if(input==null||!input.isObject()||input.size()>100)throw bad("Invalid qualifier pins");if(!kind.equals("VERIFICATION")){if(!input.isEmpty())throw bad("Only verification pins a qualified scope");return input;}var expected=qualifierPins(space,id(content,"claimId"),number(content,"claimVersion",false));if(!samePins(expected,input)||!qualifierContext(space,id(content,"claimId"),number(content,"claimVersion",false)))throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Inspect current qualifier scope before verification");return input;}
    List<Fact> qualifiers(UUID space,UUID claim){var c=get(space,claim);if(!c.kind().equals("CLAIM"))throw bad("Claim required");return jdbc.queryForList("SELECT record_id FROM public_knowledge_record WHERE space_id=? AND kind='QUALIFIER' AND content->>'claimId'=? AND content->>'claimVersion'=? ORDER BY record_id LIMIT 100",UUID.class,space,claim.toString(),Long.toString(c.version())).stream().map(id->get(space,id)).toList();}
    static void fields(JsonNode v,String... names){if(v.size()!=names.length||Arrays.stream(names).anyMatch(n->!v.has(n)))throw bad("Exact typed fields required");}
    static String str(JsonNode v,String name){if(!v.path(name).isString())throw bad("Text field required");return v.path(name).asString();}
    static UUID id(JsonNode v,String name){try{UUID id=UUID.fromString(str(v,name));ConceptStore.id(id);if(!id.toString().equals(str(v,name)))throw bad("Canonical UUID required");return id;}catch(IllegalArgumentException e){throw bad("UUIDv7 required");}}
    static long number(JsonNode v,String name,boolean zero){var n=v.path(name);if(!n.isIntegralNumber()||!n.canConvertToLong()||n.asLong()<(zero?0:1))throw bad("Positive version required");return n.asLong();}
    static String text(String value,int max,boolean required){if(value==null||value.indexOf(0)>=0||value.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff)||value.codePointCount(0,value.length())>max||required&&value.isBlank())throw bad("Invalid bounded text");return value.strip();}
    private static void put(JsonNode node,String field,String value){((tools.jackson.databind.node.ObjectNode)node).put(field,value);}
    static void paging(UUID after,int limit){if(after!=null)ConceptStore.id(after);if(limit<1||limit>50)throw bad("Page limit must be1..50");}
    private static void trace(String value){if(value==null||!value.matches("[0-9a-f]{32}")||value.equals("0".repeat(32)))throw bad("Valid trace required");}
    static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Public fact or reference unavailable");}
    private static ResponseStatusException stale(){return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Public revision changed; inspect again");}
}
