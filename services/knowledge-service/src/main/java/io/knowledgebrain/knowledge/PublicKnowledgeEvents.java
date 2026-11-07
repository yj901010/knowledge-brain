package io.knowledgebrain.knowledge;
import io.knowledgebrain.contracts.knowledge.publicknowledge.v1.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonical PUBLIC stream, published inside the owning mutation transaction. */
final class PublicKnowledgeEvents {
 static void proposal(JdbcTemplate jdbc,UuidV7 ids,Clock clock,PublicRecordStore.Proposal p,UUID actor,String trace){
  UUID event=ids.next();long time=clock.millis();var payload=PublicKnowledgeChangedPayloadV1.newBuilder().setEntityId(p.proposalId().toString()).setKind(p.kind()).setVersion(p.version()).setState(p.state()).setTitle(p.title()).setContentJson(p.content().toString()).setQualifierPinsJson(p.qualifierPins().toString()).setVisibility("PUBLIC").build();
  byte[] bytes=EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("PublicKnowledgeProposalChanged").setEventVersion(1).setOccurredAtEpochMillis(time).setProducer("knowledge-service").setAggregateType("PublicKnowledgeProposal").setAggregateId(p.proposalId().toString()).setSpaceId(p.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build().toByteArray();
  jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'public-knowledge',?,'PublicKnowledgeProposalChanged',?,?,'knowledge','public-knowledge-events','1',?)",event,p.proposalId(),new Timestamp(time),trace,bytes);
 }
 static byte[] wire(UUID event,UUID revision,UUID entity,UUID space,UUID actor,String trace,long time,String kind,long version,String state,String title,JsonNode content,JsonNode pins){
  var payload=PublicKnowledgeChangedPayloadV1.newBuilder().setEntityId(entity.toString()).setKind(kind).setVersion(version).setState(state).setTitle(title).setContentJson(content.toString()).setQualifierPinsJson(pins.toString()).setRevisionId(revision.toString()).setVisibility("PUBLIC").build();
  return EventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType("PublicKnowledgeChanged").setEventVersion(1).setOccurredAtEpochMillis(time).setProducer("knowledge-service").setAggregateType("PublicKnowledge").setAggregateId(entity.toString()).setSpaceId(space.toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build().toByteArray();
 }
 static void publish(JdbcTemplate jdbc,UuidV7 ids,Clock clock,UUID revision,UUID entity,UUID space,UUID actor,String trace,String kind,long version,String state,String title,JsonNode content,JsonNode pins){
  UUID event=ids.next();long time=clock.millis();byte[] bytes=wire(event,revision,entity,space,actor,trace,time,kind,version,state,title,content,pins);
  jdbc.update("INSERT INTO outbox_event(id,aggregatetype,aggregateid,type,occurred_at,trace_id,schema_group,schema_artifact,schema_version,payload) VALUES (?,'public-knowledge',?,'PublicKnowledgeChanged',?,?,'knowledge','public-knowledge-events','1',?)",event,entity,new Timestamp(time),trace,bytes);
 }
}
