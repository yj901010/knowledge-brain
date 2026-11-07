package io.knowledgebrain.notification;
import java.time.*;import java.sql.Timestamp;import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;import io.knowledgebrain.contracts.notification.inapp.v1.*;
import io.knowledgebrain.contracts.notification.policy.v1.*;
final class NotificationStore {
 record Notification(UUID notificationId,UUID spaceId,String visibility,String state,long version,boolean read,DispatchEvent.Source source,UUID originEventId,String deliveredAt,String updatedAt) {}
 record Revision(UUID revisionId,UUID notificationId,long version,UUID actorId,String occurredAt,Notification snapshot) {}
 record Page(UUID spaceId,String state,String readFilter,List<Notification> items,String nextAfter) {}
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;private final JsonMapper json=JsonMapper.builder().build();
 NotificationStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);}
 private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid notification input");}
 static UUID id(String raw){try{return DispatchEvent.id(raw);}catch(RuntimeException e){throw bad();}}
 static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 boolean seeded(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT seeded FROM notification_runtime WHERE singleton",Boolean.class));}
 void seededNow(){jdbc.update("UPDATE notification_runtime SET seeded=true WHERE singleton");}
 boolean accept(byte[] bytes){var event=DispatchEvent.decode(bytes);var envelope=event.envelope();String hash=hash(bytes);return Boolean.TRUE.equals(tx.execute(status->{
  int inserted=jdbc.update("INSERT INTO notification_inbox(event_id,payload_hash) VALUES (?,?) ON CONFLICT DO NOTHING",id(envelope.getEventId()),hash);
  if(inserted==0){if(!hash.equals(jdbc.queryForObject("SELECT payload_hash FROM notification_inbox WHERE event_id=?",String.class,id(envelope.getEventId()))))throw DispatchEvent.bad();return false;}
  if(event.source()==null)return false;
  UUID space=id(envelope.getSpaceId());
  lockPreference(space);
  // Both outcomes are canonical. A different event cannot reuse a dispatch identity,
  // including across a preference change or a recovery backfill.
  if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification WHERE space_id=? AND delivery_id=? UNION ALL SELECT 1 FROM notification_suppression WHERE space_id=? AND delivery_id=?)",Boolean.class,space,event.source().deliveryId(),space,event.source().deliveryId())))throw DispatchEvent.bad();
  var preference=preference(space);
  if(!preference.inAppEnabled()){
   var v=new Suppression(ids.next(),space,"PRIVATE",id(envelope.getEventId()),event.source(),preference.preferenceId(),preference.version(),"PREFERENCE_DISABLED",clock.instant().toString());
   jdbc.update("INSERT INTO notification_suppression VALUES (?,?,?,?,?,?,?,?::jsonb)",v.suppressionId(),space,v.source().deliveryId(),v.originEventId(),v.preferenceId(),v.preferenceVersion(),bytes,json.writeValueAsString(v));
   var payload=NotificationSuppressionPayloadV1.newBuilder().setSuppressionId(v.suppressionId().toString()).setPreferenceId(v.preferenceId().toString()).setPreferenceVersion(v.preferenceVersion()).setSourceEventId(v.originEventId().toString()).setDispatchId(v.source().deliveryId().toString()).setReason(v.reason()).setProcessedAt(v.processedAt()).setSourceDispatchEnvelope(com.google.protobuf.ByteString.copyFrom(bytes)).setVisibility("PRIVATE").build();
   policyEvent(v.suppressionId(),space,id(envelope.getActorId()),envelope.getTraceId(),v.processedAt(),"NotificationSuppression","NotificationDeliverySuppressed",payload.toByteArray());
   return false;
  }
  var now=clock.instant().toString();var v=new Notification(ids.next(),id(envelope.getSpaceId()),"PRIVATE","ACTIVE",1,false,event.source(),id(envelope.getEventId()),now,now);
  jdbc.update("INSERT INTO notification VALUES (?,?,?,?,'ACTIVE',false,1,?,?::jsonb)",v.notificationId(),v.spaceId(),v.source().deliveryId(),v.originEventId(),bytes,json.writeValueAsString(v));
  revision(v,id(envelope.getActorId()),envelope.getTraceId(),bytes,"NotificationCreated");return true;
 }));}
 record Preference(UUID preferenceId,UUID spaceId,String visibility,long version,boolean inAppEnabled,String updatedAt) {}
 record PreferenceRevision(UUID revisionId,long version,UUID actorId,String occurredAt,Preference snapshot) {}
 record Suppression(UUID suppressionId,UUID spaceId,String visibility,UUID originEventId,DispatchEvent.Source source,UUID preferenceId,long preferenceVersion,String reason,String processedAt) {}
 record SuppressionPage(UUID spaceId,List<Suppression> items,String nextAfter) {}

 private void lockPreference(UUID space){jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,space.getMostSignificantBits()^space.getLeastSignificantBits());}
 Preference preference(UUID space){
  var rows=jdbc.query("SELECT snapshot::text FROM notification_preference WHERE space_id=?",(rs,n)->json.readValue(rs.getString(1),Preference.class),space);
  return rows.isEmpty()?new Preference(null,space,"PRIVATE",0,true,null):rows.getFirst();
 }
 Preference preference(UUID space,UUID actor,long expected,boolean enabled,String trace){
  if(expected<0||trace==null||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32)))throw bad();
  return tx.execute(status->{
   lockPreference(space);var prior=preference(space);
   if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Notification preference changed; inspect latest");
   if(prior.inAppEnabled()==enabled)return prior;
   var now=clock.instant().toString();var v=new Preference(prior.preferenceId()==null?ids.next():prior.preferenceId(),space,"PRIVATE",Math.addExact(prior.version(),1),enabled,now);
   jdbc.update("INSERT INTO notification_preference VALUES (?,?,?,?,?::jsonb) ON CONFLICT(space_id) DO UPDATE SET version=EXCLUDED.version,in_app_enabled=EXCLUDED.in_app_enabled,snapshot=EXCLUDED.snapshot",space,v.preferenceId(),v.version(),enabled,json.writeValueAsString(v));
   var revision=ids.next();jdbc.update("INSERT INTO notification_preference_revision VALUES (?,?,?,?,?,?::jsonb)",revision,v.preferenceId(),v.version(),actor,Timestamp.from(Instant.parse(now)),json.writeValueAsString(v));
   var payload=NotificationPreferencePayloadV1.newBuilder().setPreferenceId(v.preferenceId().toString()).setRevisionId(revision.toString()).setVersion(v.version()).setVisibility("PRIVATE").setInAppEnabled(enabled).setUpdatedAt(now).build();
   policyEvent(v.preferenceId(),space,actor,trace,now,"NotificationPreference","NotificationPreferenceChanged",payload.toByteArray());return v;
  });
 }
 List<PreferenceRevision> preferenceHistory(UUID space,long before,int limit){
  if(before<1||limit<1||limit>10)throw bad();
  return jdbc.query("SELECT r.revision_id,r.version,r.actor_id,r.occurred_at,r.snapshot::text FROM notification_preference_revision r JOIN notification_preference p USING(preference_id) WHERE p.space_id=? AND r.version<? ORDER BY r.version DESC LIMIT ?",(rs,n)->new PreferenceRevision(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Preference.class)),space,before,limit);
 }
 SuppressionPage suppressions(UUID space,String after,int limit){
  if(limit<1||limit>100)throw bad();var last=position(space,"SUPPRESSED","PREFERENCE_DISABLED",after);
  var rows=last==null?jdbc.query("SELECT snapshot::text FROM notification_suppression WHERE space_id=? ORDER BY suppression_id LIMIT ?",(rs,n)->json.readValue(rs.getString(1),Suppression.class),space,limit+1):jdbc.query("SELECT snapshot::text FROM notification_suppression WHERE space_id=? AND suppression_id>? ORDER BY suppression_id LIMIT ?",(rs,n)->json.readValue(rs.getString(1),Suppression.class),space,last,limit+1);
  var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
  return new SuppressionPage(space,page,rows.size()>limit?cursor(space,"SUPPRESSED","PREFERENCE_DISABLED",page.getLast().suppressionId()):null);
 }
 private void policyEvent(UUID aggregate,UUID space,UUID actor,String trace,String now,String aggregateType,String type,byte[] payload){
  var event=ids.next();var at=Instant.parse(now);
  var envelope=NotificationPolicyEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(at.toEpochMilli()).setProducer("notification-service").setAggregateType(aggregateType).setAggregateId(aggregate.toString()).setSpaceId(space.toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(com.google.protobuf.ByteString.copyFrom(payload)).build();
  jdbc.update("INSERT INTO outbox_event VALUES (?,'delivery-policy',?,?,?,?, 'notification','delivery-policy-events','1',?)",event,aggregate,type,Timestamp.from(at),trace,envelope.toByteArray());
 }
 private Notification current(UUID space,UUID id,boolean lock){var rows=jdbc.query("SELECT snapshot::text FROM notification WHERE space_id=? AND notification_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Notification.class),space,id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification unavailable");return rows.getFirst();}
 Notification get(UUID space,UUID id){return current(space,id,false);}
 Notification change(UUID space,UUID id,UUID actor,long expected,Boolean read,boolean retire,String trace){if(expected<1||!trace.matches("[0-9a-f]{32}")||trace.equals("0".repeat(32))||!retire&&read==null)throw bad();return tx.execute(status->{var prior=current(space,id,true);if(prior.version()!=expected)throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Notification changed; inspect latest");if(prior.state().equals("RETIRED")){if(retire)return prior;throw new ResponseStatusException(HttpStatus.NOT_FOUND);}if(!retire&&prior.read()==read)return prior;var v=new Notification(id,space,"PRIVATE",retire?"RETIRED":"ACTIVE",Math.addExact(prior.version(),1),retire?prior.read():read,prior.source(),prior.originEventId(),prior.deliveredAt(),clock.instant().toString());jdbc.update("UPDATE notification SET state=?,is_read=?,version=?,snapshot=?::jsonb WHERE notification_id=?",v.state(),v.read(),v.version(),json.writeValueAsString(v),id);byte[] bytes=jdbc.queryForObject("SELECT source_envelope FROM notification WHERE notification_id=?",byte[].class,id);revision(v,actor,trace,bytes,retire?"NotificationRetired":"NotificationReadChanged");return v;});}
 private String cursor(UUID space,String state,String read,UUID last){return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|",space.toString(),state,read,last.toString()).getBytes(StandardCharsets.US_ASCII));}
 private UUID position(UUID space,String state,String read,String raw){if(raw==null)return null;try{if(!raw.matches("[A-Za-z0-9_-]{1,256}"))throw bad();var bytes=Base64.getUrlDecoder().decode(raw);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw))throw bad();var v=new String(bytes,StandardCharsets.US_ASCII).split("\\|",-1);if(v.length!=4||!v[0].equals(space.toString())||!v[1].equals(state)||!v[2].equals(read))throw bad();return id(v[3]);}catch(IllegalArgumentException e){throw bad();}}
 Page list(UUID space,String state,String read,String after,int limit){if(state==null||read==null||!Set.of("ACTIVE","RETIRED").contains(state)||!Set.of("ALL","UNREAD","READ").contains(read)||limit<1||limit>100)throw bad();var cursor=position(space,state,read,after);var args=new ArrayList<Object>(List.of(space,state));String sql="SELECT snapshot::text FROM notification WHERE space_id=? AND state=?";if(!read.equals("ALL")){sql+=" AND is_read=?";args.add(read.equals("READ"));}if(cursor!=null){sql+=" AND notification_id>?";args.add(cursor);}sql+=" ORDER BY notification_id LIMIT ?";args.add(limit+1);var rows=jdbc.query(sql,(rs,n)->json.readValue(rs.getString(1),Notification.class),args.toArray());var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(space,state,read,page,rows.size()>limit?cursor(space,state,read,page.getLast().notificationId()):null);}
 List<Revision> history(UUID space,UUID id,long before,int limit){current(space,id,false);if(before<1||limit<1||limit>10)throw bad();return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM notification_revision WHERE notification_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),id,rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Notification.class)),id,before,limit);}
 private void revision(Notification v,UUID actor,String trace,byte[] source,String type){var revision=ids.next();var event=ids.next();var now=Instant.parse(v.updatedAt());jdbc.update("INSERT INTO notification_revision VALUES (?,?,?,?,?,?::jsonb)",revision,v.notificationId(),v.version(),actor,Timestamp.from(now),json.writeValueAsString(v));var payload=InAppNotificationPayloadV1.newBuilder().setNotificationId(v.notificationId().toString()).setRevisionId(revision.toString()).setVersion(v.version()).setVisibility("PRIVATE").setState(v.state()).setRead(v.read()).setDeliveredAt(v.deliveredAt()).setUpdatedAt(v.updatedAt()).setDispatchId(v.source().deliveryId().toString()).setSourceEventId(v.originEventId().toString()).setSourceDispatchEnvelope(com.google.protobuf.ByteString.copyFrom(source)).build();var envelope=InAppNotificationEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("notification-service").setAggregateType("Notification").setAggregateId(v.notificationId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build();jdbc.update("INSERT INTO outbox_event VALUES (?,'in-app',?,?,?,?, 'notification','in-app-events','1',?)",event,v.notificationId(),type,Timestamp.from(now),trace,envelope.toByteArray());}
}
