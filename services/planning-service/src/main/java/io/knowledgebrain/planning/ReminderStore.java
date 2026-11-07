package io.knowledgebrain.planning;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import io.knowledgebrain.contracts.planning.reminder.v1.*;

/** Own canonical settings, immutable source pins, revisions and Outbox. No delivery is inferred. */
final class ReminderStore {
    record Reminder(UUID reminderId, UUID spaceId, String visibility, String state, long version,
                    ReminderRules.Input input, ReminderRules.SchedulePin schedule, String remindsAt,
                    String remindsLocal, String remindsOffset, String previewHash, String createdAt, String updatedAt) {}
    record Creation(Reminder reminder, boolean replay) {}
    record Page(UUID spaceId, String state, String scheduleFilter, List<Reminder> items, String nextAfter) {}
    record Revision(UUID revisionId, UUID reminderId, long version, UUID actorId, String occurredAt, Reminder snapshot) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final UuidV7 ids;
    private final JsonMapper json = JsonMapper.builder().build();

    ReminderStore(DataSource source, Clock clock) {
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(15);
        this.clock = clock;
        ids = new UuidV7(clock);
    }

    static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Reminder or source changed; inspect and preview again");
    }
    static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Reminder unavailable");
    }

    private ScheduleStore.Schedule source(UUID space, UUID schedule, boolean lock) {
        if (lock) {
            var heads = jdbc.query("SELECT version FROM schedule WHERE space_id=? AND schedule_id=? AND state='ACTIVE' FOR UPDATE",
                    (rs, n) -> rs.getLong(1), space, schedule);
            if (heads.isEmpty()) throw ScheduleStore.missing();
            return json.readValue(jdbc.queryForObject("SELECT snapshot::text FROM schedule_revision WHERE schedule_id=? AND version=?",
                    String.class, schedule, heads.getFirst()), ScheduleStore.Schedule.class);
        }
        var rows = jdbc.query("SELECT r.snapshot::text FROM schedule s JOIN schedule_revision r ON r.schedule_id=s.schedule_id AND r.version=s.version WHERE s.space_id=? AND s.schedule_id=? AND s.state='ACTIVE'",
                (rs, n) -> json.readValue(rs.getString(1), ScheduleStore.Schedule.class), space, schedule);
        if (rows.isEmpty()) throw ScheduleStore.missing();
        return rows.getFirst();
    }

    ReminderRules.Preview preview(UUID space, ReminderRules.Input input) {
        ScheduleRules.id(space); ReminderRules.input(input);
        return ReminderRules.preview(space, input, source(space, input.scheduleId(), false));
    }

    private ReminderRules.Preview confirmed(UUID space, ReminderRules.Input input, String hash) {
        var result = ReminderRules.preview(space, input, source(space, input.scheduleId(), true));
        if (!result.previewHash().equals(hash)) throw stale();
        return result;
    }

    Creation create(UUID space, UUID actor, String key, ReminderRules.Input input, String previewHash, String trace) {
        ScheduleRules.id(space); ScheduleRules.id(actor); ReminderRules.input(input);
        RecurrenceRules.hash(previewHash); GoalRules.trace(trace);
        if (key == null || !key.matches("[0-9a-f]{32}")) throw ScheduleRules.bad();
        String intent = RecurrenceRules.hash(List.of(input, previewHash));
        return tx.execute(status -> {
            var id = ids.next(); var now = clock.instant();
            int inserted = jdbc.update("INSERT INTO reminder(reminder_id,space_id,schedule_id,visibility,state,version,minutes_before,request_key,request_hash,created_at,updated_at) VALUES (?,?,?,'PRIVATE','ACTIVE',1,?,?,?,?,?) ON CONFLICT(space_id,request_key) DO NOTHING",
                    id, space, input.scheduleId(), input.minutesBefore(), key, intent, Timestamp.from(now), Timestamp.from(now));
            if (inserted == 0) {
                var original = jdbc.query("SELECT m.request_hash,r.snapshot::text FROM reminder m JOIN reminder_revision r ON r.reminder_id=m.reminder_id AND r.version=1 WHERE m.space_id=? AND m.request_key=?",
                        (rs,n) -> Map.entry(rs.getString(1),rs.getString(2)), space, key);
                if (original.size() != 1) throw new IllegalStateException("Original reminder creation missing");
                if (!original.getFirst().getKey().equals(intent))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Creation key already represents different reminder input");
                return new Creation(json.readValue(original.getFirst().getValue(), Reminder.class), true);
            }
            var pin = confirmed(space, input, previewHash);
            var value = value(id, space, 1, "ACTIVE", pin, now.toString(), now.toString());
            revision(value, actor, trace, "ReminderCreated");
            return new Creation(value, false);
        });
    }

    private Reminder value(UUID id, UUID space, long version, String state, ReminderRules.Preview pin, String created, String updated) {
        return new Reminder(id, space, "PRIVATE", state, version, pin.input(), pin.schedule(), pin.remindsAt(),
                pin.remindsLocal(), pin.remindsOffset(), pin.previewHash(), created, updated);
    }

    private Reminder current(UUID space, UUID id, boolean lock) {
        ScheduleRules.id(space); ScheduleRules.id(id);
        if (lock) {
            var versions = jdbc.query("SELECT version FROM reminder WHERE space_id=? AND reminder_id=? FOR UPDATE", (rs,n)->rs.getLong(1), space, id);
            if (versions.isEmpty()) throw missing();
            return decode(jdbc.queryForObject("SELECT snapshot::text FROM reminder_revision WHERE reminder_id=? AND version=?", String.class, id, versions.getFirst()), space, id);
        }
        var rows = jdbc.query("SELECT r.snapshot::text FROM reminder m JOIN reminder_revision r ON r.reminder_id=m.reminder_id AND r.version=m.version WHERE m.space_id=? AND m.reminder_id=?",
                (rs,n)->decode(rs.getString(1),space,id),space,id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }
    private Reminder decode(String raw, UUID space, UUID id) {
        if (raw == null) throw new IllegalStateException("Reminder revision missing");
        var v = json.readValue(raw, Reminder.class);
        if (!v.spaceId().equals(space) || !v.reminderId().equals(id) || !v.visibility().equals("PRIVATE"))
            throw new IllegalStateException("Reminder revision mismatch");
        return v;
    }
    Reminder get(UUID space, UUID id) { return current(space, id, false); }

    Reminder change(UUID space, UUID id, UUID actor, long expected, ReminderRules.Input input, String hash, boolean retire, String trace) {
        ScheduleRules.id(actor); GoalRules.trace(trace);
        if (expected < 1) throw ScheduleRules.bad();
        if (!retire) { ReminderRules.input(input); RecurrenceRules.hash(hash); }
        return tx.execute(status -> {
            // Schedule-before-Reminder order leaves future source-aware dispatch compatible with this transaction.
            var observed = current(space,id,false);
            if (!retire && !observed.input().scheduleId().equals(input.scheduleId())) throw ScheduleRules.bad();
            var pin = retire ? null : confirmed(space,input,hash);
            var prior = current(space,id,true);
            if (prior.version() != expected) throw stale();
            if (prior.state().equals("RETIRED")) {
                if (retire) return prior;
                throw missing();
            }
            if (!retire && prior.input().equals(input) && prior.previewHash().equals(hash)) return prior;
            var now = clock.instant().toString();
            var v = retire ? new Reminder(id,space,"PRIVATE","RETIRED",Math.addExact(prior.version(),1),prior.input(),prior.schedule(),
                    prior.remindsAt(),prior.remindsLocal(),prior.remindsOffset(),prior.previewHash(),prior.createdAt(),now)
                    : value(id,space,Math.addExact(prior.version(),1),"ACTIVE",pin,prior.createdAt(),now);
            jdbc.update("UPDATE reminder SET state=?,version=?,minutes_before=?,updated_at=? WHERE reminder_id=?",v.state(),v.version(),v.input().minutesBefore(),Timestamp.from(Instant.parse(now)),id);
            revision(v,actor,trace,retire?"ReminderRetired":"ReminderUpdated");
            return v;
        });
    }

    Page list(UUID space, String state, String schedule, String after, int limit) {
        ScheduleRules.id(space);
        if (state == null || !Set.of("ACTIVE","RETIRED").contains(state) || schedule == null || limit < 1 || limit > 100) throw ScheduleRules.bad();
        if (!schedule.equals("ALL")) ScheduleRules.id(schedule);
        var position = ReminderRules.cursor(after,space,state,schedule);
        var args = new ArrayList<Object>(List.of(space,state));
        String sql = "SELECT r.snapshot::text FROM reminder m JOIN reminder_revision r ON r.reminder_id=m.reminder_id AND r.version=m.version WHERE m.space_id=? AND m.state=?";
        if (!schedule.equals("ALL")) { sql+=" AND m.schedule_id=?"; args.add(UUID.fromString(schedule)); }
        if (position != null) { sql+=" AND m.reminder_id>?"; args.add(position); }
        sql+=" ORDER BY m.reminder_id LIMIT ?"; args.add(limit+1);
        var rows=jdbc.query(sql,(rs,n)->json.readValue(rs.getString(1),Reminder.class),args.toArray());
        var page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page(space,state,schedule,page,rows.size()>limit?ReminderRules.cursor(space,state,schedule,page.getLast().reminderId()):null);
    }

    List<Revision> history(UUID space, UUID id, long before, int limit) {
        current(space,id,false);
        if (before < 1 || limit < 1 || limit > 10) throw ScheduleRules.bad();
        return jdbc.query("SELECT revision_id,version,actor_id,occurred_at,snapshot::text FROM reminder_revision WHERE reminder_id=? AND version<? ORDER BY version DESC LIMIT ?",
                (rs,n)->new Revision(rs.getObject(1,UUID.class),id,rs.getLong(2),rs.getObject(3,UUID.class),rs.getTimestamp(4).toInstant().toString(),decode(rs.getString(5),space,id)),id,before,limit);
    }

    private void revision(Reminder v, UUID actor, String trace, String type) {
        var revision=ids.next(); var event=ids.next(); var now=Instant.parse(v.updatedAt()); var p=v.schedule();
        jdbc.update("INSERT INTO reminder_revision VALUES (?,?,?,?,?,?::jsonb)",revision,v.reminderId(),v.version(),actor,Timestamp.from(now),json.writeValueAsString(v));
        var pin=ReminderSchedulePinV1.newBuilder().setScheduleId(p.scheduleId().toString()).setVersion(p.version()).setTitle(p.title())
                .setStartsAt(p.startsAt()).setEndsAt(p.endsAt()).setTimeZone(p.timeZone()).setStartsLocal(p.startsLocal()).setEndsLocal(p.endsLocal())
                .setStartsOffset(p.startsOffset()).setEndsOffset(p.endsOffset()).build();
        var payload=ReminderChangedPayloadV1.newBuilder().setReminderId(v.reminderId().toString()).setRevisionId(revision.toString())
                .setVersion(v.version()).setVisibility("PRIVATE").setState(v.state()).setMinutesBefore(v.input().minutesBefore())
                .setSchedule(pin).setRemindsAt(v.remindsAt()).setRemindsLocal(v.remindsLocal()).setRemindsOffset(v.remindsOffset())
                .setPreviewHash(v.previewHash()).setCreatedAt(v.createdAt()).setUpdatedAt(v.updatedAt()).build();
        var envelope=ReminderEventEnvelopeV1.newBuilder().setEventId(event.toString()).setEventType(type).setEventVersion(1)
                .setOccurredAtEpochMillis(now.toEpochMilli()).setProducer("planning-service").setAggregateType("Reminder")
                .setAggregateId(v.reminderId().toString()).setSpaceId(v.spaceId().toString()).setActorId(actor.toString())
                .setTraceId(trace).setPayload(payload.toByteString()).build();
        jdbc.update("INSERT INTO outbox_event VALUES (?,'reminder',?,?,?,?, 'planning','reminder-events','1',?)",event,v.reminderId(),type,Timestamp.from(now),trace,envelope.toByteArray());
    }
}
