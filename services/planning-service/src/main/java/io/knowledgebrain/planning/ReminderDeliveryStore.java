package io.knowledgebrain.planning;

import java.time.*;import java.sql.Timestamp;import java.util.*;import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.planning.delivery.v1.*;

/** Planning-local durable dispatch intent; all source locks precede the attempt lock. */
final class ReminderDeliveryStore {
    record Delivery(UUID deliveryId,UUID spaceId,UUID reminderId,long version,String state,String reason,
                    ReminderStore.Reminder reminder,UUID armedBy,String traceId,String createdAt,String updatedAt,String triggeredAt) {}
    record Creation(Delivery delivery,boolean replay) {}
    record Page(UUID spaceId,UUID reminderId,List<Delivery> items,String nextAfter) {}
    record Revision(UUID revisionId,UUID deliveryId,long version,UUID actorId,String occurredAt,Delivery snapshot) {}
    record Export(UUID deliveryId,String envelope) {}
    record ExportPage(List<Export> items,String nextAfter) {}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Clock clock;private final UuidV7 ids;
    private final JsonMapper json=JsonMapper.builder().build();private final ReminderStore reminders;
    ReminderDeliveryStore(DataSource source,Clock clock){jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(15);this.clock=clock;ids=new UuidV7(clock);reminders=new ReminderStore(source,clock);}
    private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Reminder delivery unavailable");}
    private void sourceLocks(UUID space,UUID reminder){
        var schedule=jdbc.query("SELECT schedule_id FROM reminder WHERE space_id=? AND reminder_id=?",(rs,n)->rs.getObject(1,UUID.class),space,reminder);
        if(schedule.isEmpty())throw ReminderStore.missing();
        jdbc.query("SELECT schedule_id FROM schedule WHERE space_id=? AND schedule_id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),space,schedule.getFirst());
        jdbc.query("SELECT reminder_id FROM reminder WHERE space_id=? AND reminder_id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),space,reminder);
    }
    private Delivery current(UUID space,UUID reminder,UUID id,boolean lock){
        var rows=jdbc.query("SELECT snapshot::text FROM reminder_delivery WHERE space_id=? AND reminder_id=? AND delivery_id=?"+(lock?" FOR UPDATE":""),(rs,n)->json.readValue(rs.getString(1),Delivery.class),space,reminder,id);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    Delivery get(UUID space,UUID reminder,UUID id){return current(space,reminder,id,false);}
    Creation arm(UUID space,UUID reminder,UUID actor,long expected,String key,String trace){
        for(var id:List.of(space,reminder,actor))ScheduleRules.id(id);GoalRules.trace(trace);
        if(expected<1||key==null||!key.matches("[0-9a-f]{32}"))throw ScheduleRules.bad();
        String hash=RecurrenceRules.hash(List.of(reminder,expected));
        return tx.execute(status->{
            // The immutable Reminder link is read first, but no row is held out of order.
            sourceLocks(space,reminder);
            var replay=jdbc.query("SELECT request_hash,snapshot::text FROM reminder_delivery WHERE space_id=? AND request_key=?",(rs,n)->Map.entry(rs.getString(1),rs.getString(2)),space,key);
            if(!replay.isEmpty()){if(!replay.getFirst().getKey().equals(hash))throw new ResponseStatusException(HttpStatus.CONFLICT,"Delivery key represents different intent");return new Creation(json.readValue(replay.getFirst().getValue(),Delivery.class),true);}
            var source=reminders.get(space,reminder);
            if(source.version()!=expected)throw ReminderStore.stale();
            if(!source.state().equals("ACTIVE"))throw ReminderStore.missing();
            var heads=jdbc.query("SELECT version,state FROM schedule WHERE space_id=? AND schedule_id=?",(rs,n)->Map.entry(rs.getLong(1),rs.getString(2)),space,source.input().scheduleId());
            if(heads.isEmpty()||!heads.getFirst().getValue().equals("ACTIVE"))throw ScheduleStore.missing();
            if(heads.getFirst().getKey()!=source.input().scheduleVersion())throw ReminderStore.stale();
            var now=clock.instant();if(!Instant.parse(source.remindsAt()).isAfter(now))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Only future reminder times can be armed");
            // A different key cannot silently replace an outstanding attempt.
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reminder_delivery WHERE reminder_id=? AND state='PENDING')",Boolean.class,reminder)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Cancel the pending delivery before arming another");
            var v=new Delivery(ids.next(),space,reminder,1,"PENDING","",source,actor,trace,now.toString(),now.toString(),null);
            jdbc.update("INSERT INTO reminder_delivery(delivery_id,space_id,reminder_id,reminder_version,schedule_id,schedule_version,state,version,due_at,snapshot,request_key,request_hash) VALUES (?,?,?,?,?,?,'PENDING',1,?,?::jsonb,?,?)",v.deliveryId(),space,reminder,source.version(),source.input().scheduleId(),source.input().scheduleVersion(),OffsetDateTime.ofInstant(Instant.parse(source.remindsAt()),ZoneOffset.UTC),json.writeValueAsString(v),key,hash);
            revision(v,actor,trace,"ReminderDeliveryArmed");return new Creation(v,false);
        });
    }
    Delivery cancel(UUID space,UUID reminder,UUID id,UUID actor,long expected,String trace){
        ScheduleRules.id(actor);GoalRules.trace(trace);if(expected<1)throw ScheduleRules.bad();
        return tx.execute(status->{sourceLocks(space,reminder);var prior=current(space,reminder,id,true);if(prior.version()!=expected)throw ReminderStore.stale();if(prior.state().equals("DISPATCHED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Already dispatched; inspect the notification inbox");if(!prior.state().equals("PENDING"))return prior;var v=finish(prior,"CANCELLED","USER_CANCELLED",null);save(v,actor,trace,"ReminderDeliveryCancelled");return v;});
    }
    private Delivery finish(Delivery prior,String state,String reason,String triggered){return new Delivery(prior.deliveryId(),prior.spaceId(),prior.reminderId(),Math.addExact(prior.version(),1),state,reason,prior.reminder(),prior.armedBy(),prior.traceId(),prior.createdAt(),clock.instant().toString(),triggered);}
    private void save(Delivery v,UUID actor,String trace,String type){jdbc.update("UPDATE reminder_delivery SET state=?,version=?,snapshot=?::jsonb WHERE delivery_id=?",v.state(),v.version(),json.writeValueAsString(v),v.deliveryId());revision(v,actor,trace,type);}
    List<UUID> candidates(int limit){
        if(limit<1||limit>100)throw ScheduleRules.bad();
        return jdbc.query("SELECT d.delivery_id FROM reminder_delivery d JOIN reminder m ON m.reminder_id=d.reminder_id AND m.space_id=d.space_id JOIN schedule s ON s.schedule_id=d.schedule_id AND s.space_id=d.space_id WHERE d.state='PENDING' AND (d.due_at<=? OR m.state<>'ACTIVE' OR m.version<>d.reminder_version OR s.state<>'ACTIVE' OR s.version<>d.schedule_version) ORDER BY d.due_at,d.delivery_id LIMIT ?",(rs,n)->rs.getObject(1,UUID.class),OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),limit);
    }
    boolean process(UUID id){return Boolean.TRUE.equals(tx.execute(status->{
        var observed=jdbc.query("SELECT snapshot::text FROM reminder_delivery WHERE delivery_id=?",(rs,n)->json.readValue(rs.getString(1),Delivery.class),id);
        if(observed.isEmpty())return false;var hint=observed.getFirst();sourceLocks(hint.spaceId(),hint.reminderId());var prior=current(hint.spaceId(),hint.reminderId(),id,true);if(!prior.state().equals("PENDING"))return false;
        var reminder=reminders.get(prior.spaceId(),prior.reminderId());var schedule=jdbc.queryForMap("SELECT version,state FROM schedule WHERE schedule_id=? AND space_id=?",prior.reminder().input().scheduleId(),prior.spaceId());
        boolean valid=reminder.state().equals("ACTIVE")&&reminder.version()==prior.reminder().version()&&schedule.get("state").equals("ACTIVE")&&((Number)schedule.get("version")).longValue()==prior.reminder().input().scheduleVersion();
        if(!valid){save(finish(prior,"SUPPRESSED","SOURCE_CHANGED",null),prior.armedBy(),prior.traceId(),"ReminderDeliverySuppressed");return true;}
        if(clock.instant().isBefore(Instant.parse(prior.reminder().remindsAt())))return false;
        var v=finish(prior,"DISPATCHED","",clock.instant().toString());save(v,prior.armedBy(),prior.traceId(),"ReminderDispatched");return true;
    }));}
    Page list(UUID space,UUID reminder,String after,int limit){reminders.get(space,reminder);if(limit<1||limit>100)throw ScheduleRules.bad();var cursor=ReminderRules.cursor(after,space,"DELIVERY",reminder.toString());var rows=jdbc.query("SELECT snapshot::text FROM reminder_delivery WHERE space_id=? AND reminder_id=?"+(cursor==null?"":" AND delivery_id>?")+" ORDER BY delivery_id LIMIT ?",(rs,n)->json.readValue(rs.getString(1),Delivery.class),cursor==null?new Object[]{space,reminder,limit+1}:new Object[]{space,reminder,cursor,limit+1});var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new Page(space,reminder,page,rows.size()>limit?ReminderRules.cursor(space,"DELIVERY",reminder.toString(),page.getLast().deliveryId()):null);}
    List<Revision> history(UUID space,UUID reminder,UUID id,long before,int limit){current(space,reminder,id,false);if(before<1||limit<1||limit>10)throw ScheduleRules.bad();return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM reminder_delivery_revision WHERE delivery_id=? AND version<? ORDER BY version DESC LIMIT ?",(rs,n)->new Revision(rs.getObject(1,UUID.class),id,rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),json.readValue(rs.getString(5),Delivery.class)),id,before,limit);}
    ExportPage export(UUID after,int limit){if(limit<1||limit>100)throw ScheduleRules.bad();var rows=jdbc.query("SELECT delivery_id,dispatched_envelope FROM reminder_delivery WHERE state='DISPATCHED'"+(after==null?"":" AND delivery_id>?")+" ORDER BY delivery_id LIMIT ?",(rs,n)->new Export(rs.getObject(1,UUID.class),Base64.getEncoder().encodeToString(rs.getBytes(2))),after==null?new Object[]{limit+1}:new Object[]{after,limit+1});var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));return new ExportPage(page,rows.size()>limit?page.getLast().deliveryId().toString():null);}
    private void revision(Delivery v,UUID actor,String trace,String type){
        var now=Instant.parse(v.updatedAt());var revision=ids.next();var event=ids.next();var m=v.reminder();var s=m.schedule();
        jdbc.update("INSERT INTO reminder_delivery_revision VALUES (?,?,?,?,?,?::jsonb)",revision,v.deliveryId(),v.version(),actor,Timestamp.from(now),json.writeValueAsString(v));
        var payload=ReminderDeliveryPayloadV1.newBuilder().setDeliveryId(v.deliveryId().toString()).setReminderId(v.reminderId().toString()).setReminderVersion(m.version()).setScheduleId(s.scheduleId().toString()).setScheduleVersion(s.version()).setMinutesBefore(m.input().minutesBefore()).setTitle(s.title()).setStartsAt(s.startsAt()).setEndsAt(s.endsAt()).setTimeZone(s.timeZone()).setStartsLocal(s.startsLocal()).setEndsLocal(s.endsLocal()).setStartsOffset(s.startsOffset()).setEndsOffset(s.endsOffset()).setRemindsAt(m.remindsAt()).setRemindsLocal(m.remindsLocal()).setRemindsOffset(m.remindsOffset()).setState(v.state()).setReason(v.reason()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt()).setTriggeredAt(v.triggeredAt()==null?"":v.triggeredAt()).setArmedBy(v.armedBy().toString()).setVersion(v.version()).build();
        var envelope=ReminderDeliveryEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1).setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("planning-service").setAggregateType("ReminderDelivery").setAggregateId(v.deliveryId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString()).setTraceId(trace).setPayload(payload.toByteString()).build().toByteArray();
        jdbc.update("INSERT INTO outbox_event VALUES (?,'reminder-delivery',?,?,?,?, 'planning','reminder-delivery-events','1',?)",event,v.deliveryId(),type,Timestamp.from(now),trace,envelope);
        if(v.state().equals("DISPATCHED"))jdbc.update("UPDATE reminder_delivery SET dispatched_envelope=? WHERE delivery_id=?",envelope,v.deliveryId());
    }
}
