package io.knowledgebrain.knowledge;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

/** Canonical Knowledge-only rebuild feed. The normal Query deployment has no credential. */
@RestController
@ConditionalOnProperty(name="brain.knowledge.snapshot.enabled",havingValue="true")
final class KnowledgeSnapshotController {
 record Item(UUID entityId,UUID spaceId,long version,String payload){}
 record Page(UUID through,UUID nextAfter,List<Item> items){}
 private final JdbcTemplate jdbc;private final byte[] key;
 KnowledgeSnapshotController(DataSource source,@Value("${brain.knowledge.snapshot.key}")String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Snapshot service credential required");jdbc=new JdbcTemplate(source);this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 static UUID id(String raw){if(raw==null)return null;var id=UUID.fromString(raw);if(id.version()!=7||id.variant()!=2||!id.toString().equals(raw))throw new IllegalArgumentException();return id;}
 @GetMapping("/internal/knowledge/brain-snapshots") ResponseEntity<Page> read(HttpServletRequest request){
  if(!request.isSecure()||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");var auth=Collections.list(request.getHeaders("Authorization"));if(auth.size()!=1||!MessageDigest.isEqual(key,auth.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Snapshot credential required");if(request.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service credential only");
  try{for(var e:request.getParameterMap().entrySet())if(!Set.of("kind","limit","after","through").contains(e.getKey())||e.getValue().length!=1)throw new IllegalArgumentException();String kind=request.getParameter("kind");if(kind==null||!Set.of("CONCEPT","CLAIM").contains(kind))throw new IllegalArgumentException();UUID after=id(request.getParameter("after")),through=id(request.getParameter("through"));String raw=request.getParameter("limit");if(raw!=null&&!raw.matches("[1-5]"))throw new IllegalArgumentException();int limit=raw==null?5:Integer.parseInt(raw);if(after!=null&&(through==null||after.toString().compareTo(through.toString())>=0))throw new IllegalArgumentException();String table=kind.equals("CONCEPT")?"concept":"claim",identity=table+"_id";
   if(through==null){var ids=jdbc.query("SELECT "+identity+" FROM "+table+" ORDER BY "+identity+" DESC LIMIT 1",(rs,n)->rs.getObject(1,UUID.class));if(ids.isEmpty())return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(null,null,List.of()));through=ids.getFirst();}
   String columns=kind.equals("CONCEPT")?"r.title,r.description":"r.subject_concept_id,r.predicate_concept_id,r.object_concept_id,r.object_type,r.object_value";
   String sql="SELECT n."+identity+",n.space_id,r.revision_id,n.version,n.state,n.visibility,r.actor_id,r.occurred_at,"+columns+" FROM "+table+" n LEFT JOIN "+table+"_revision r ON r."+identity+"=n."+identity+" AND r.version=n.version WHERE n."+identity+"<=?"+(after==null?"":" AND n."+identity+">?")+" ORDER BY n."+identity+" LIMIT ?";
   var items=jdbc.query(sql,(rs,i)->{UUID entity=rs.getObject(1,UUID.class),space=rs.getObject(2,UUID.class),revision=rs.getObject(3,UUID.class);if(revision==null)throw new IllegalStateException("Canonical snapshot missing");long version=rs.getLong(4);String state=rs.getString(5),visibility=rs.getString(6),actor=rs.getString(7);long time=rs.getTimestamp(8).toInstant().toEpochMilli();byte[] wire;
    if(kind.equals("CONCEPT")){var p=io.knowledgebrain.contracts.knowledge.v1.ConceptChangedPayloadV1.newBuilder().setConceptId(entity.toString()).setRevisionId(revision.toString()).setVersion(version).setState(state).setVisibility(visibility).setTitle(rs.getString(9)).setDescription(rs.getString(10)).build();wire=io.knowledgebrain.contracts.knowledge.v1.EventEnvelopeV1.newBuilder().setEventId(revision.toString()).setProducer("knowledge-service").setAggregateType("Concept").setAggregateId(entity.toString()).setSpaceId(space.toString()).setActorId(actor).setOccurredAtEpochMillis(time).setTraceId("e".repeat(32)).setEventVersion(1).setEventType(state.equals("RETIRED")?"ConceptRetired":version==1?"ConceptCreated":"ConceptUpdated").setPayload(p.toByteString()).build().toByteArray();}
    else if(rs.getString(12).equals("CONCEPT")){var p=io.knowledgebrain.contracts.knowledge.claim.v1.ClaimChangedPayloadV1.newBuilder().setClaimId(entity.toString()).setRevisionId(revision.toString()).setVersion(version).setState(state).setVisibility(visibility).setVerificationState("DRAFT").setSubjectConceptId(rs.getString(9)).setPredicateConceptId(rs.getString(10)).setObjectType("CONCEPT").setObjectConceptId(rs.getString(11)).build();wire=io.knowledgebrain.contracts.knowledge.claim.v1.EventEnvelopeV1.newBuilder().setEventId(revision.toString()).setProducer("knowledge-service").setAggregateType("Claim").setAggregateId(entity.toString()).setSpaceId(space.toString()).setActorId(actor).setOccurredAtEpochMillis(time).setTraceId("e".repeat(32)).setEventVersion(1).setEventType(state.equals("RETIRED")?"ClaimRetired":version==1?"ClaimCreated":"ClaimUpdated").setPayload(p.toByteString()).build().toByteArray();}
    else{var p=io.knowledgebrain.contracts.knowledge.claim.v2.ClaimChangedPayloadV2.newBuilder().setClaimId(entity.toString()).setRevisionId(revision.toString()).setVersion(version).setState(state).setVisibility(visibility).setVerificationState("DRAFT").setSubjectConceptId(rs.getString(9)).setPredicateConceptId(rs.getString(10)).setObjectType(rs.getString(12)).setObjectValue(rs.getString(13)).build();wire=io.knowledgebrain.contracts.knowledge.claim.v2.EventEnvelopeV2.newBuilder().setEventId(revision.toString()).setProducer("knowledge-service").setAggregateType("Claim").setAggregateId(entity.toString()).setSpaceId(space.toString()).setActorId(actor).setOccurredAtEpochMillis(time).setTraceId("e".repeat(32)).setEventVersion(2).setEventType(state.equals("RETIRED")?"ClaimRetired":version==1?"ClaimCreated":"ClaimUpdated").setPayload(p.toByteString()).build().toByteArray();}
    return new Item(entity,space,version,Base64.getEncoder().encodeToString(wire));},after==null?new Object[]{through,limit+1}:new Object[]{through,after,limit+1});boolean more=items.size()>limit;var rows=List.copyOf(items.subList(0,Math.min(limit,items.size())));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(through,more?rows.getLast().entityId():null,rows));
  }catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid snapshot request");}
 }
}
