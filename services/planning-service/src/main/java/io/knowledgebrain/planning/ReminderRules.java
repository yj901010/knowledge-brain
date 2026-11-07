package io.knowledgebrain.planning;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** A reminder setting pins an explicitly inspected Schedule revision and elapsed minutes. */
final class ReminderRules {
    record Input(UUID scheduleId, long scheduleVersion, int minutesBefore) {}
    record SchedulePin(UUID scheduleId, long version, String title, String startsAt,
                       String endsAt, String timeZone, String startsLocal, String endsLocal,
                       String startsOffset, String endsOffset) {}
    record Preview(UUID spaceId, Input input, SchedulePin schedule, String remindsAt,
                   String remindsLocal, String remindsOffset, String previewHash) {}

    static void input(Input value) {
        if (value == null) throw ScheduleRules.bad();
        ScheduleRules.id(value.scheduleId());
        if (value.scheduleVersion() < 1 || value.minutesBefore() < 0 || value.minutesBefore() > 10080)
            throw ScheduleRules.bad();
    }

    static Preview preview(UUID space, Input input, ScheduleStore.Schedule source) {
        input(input);
        if (!space.equals(source.spaceId()) || !input.scheduleId().equals(source.scheduleId())
                || !source.state().equals("ACTIVE") || !source.visibility().equals("PRIVATE"))
            throw ScheduleStore.missing();
        if (source.version() != input.scheduleVersion()) throw ReminderStore.stale();
        var instant = ScheduleRules.instant(source.startsAt()).minusSeconds(input.minutesBefore() * 60L);
        ScheduleRules.instant(instant.toString());
        var local = instant.atZone(ScheduleRules.zone(source.timeZone()));
        if (local.getYear() < 1 || local.getYear() > 9999) throw ScheduleRules.bad();
        var pin = new SchedulePin(source.scheduleId(), source.version(), source.title(), source.startsAt(),
                source.endsAt(), source.timeZone(), source.startsLocal(), source.endsLocal(),
                source.startsOffset(), source.endsOffset());
        var value = new Preview(space, input, pin, instant.toString(), ScheduleRules.LOCAL.format(local),
                local.getOffset().toString(), null);
        return new Preview(space, input, pin, value.remindsAt(), value.remindsLocal(),
                value.remindsOffset(), RecurrenceRules.hash(value));
    }

    static String cursor(UUID space, String state, String schedule, UUID last) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.join("|", space.toString(), state, schedule, last.toString()).getBytes(StandardCharsets.US_ASCII));
    }

    static UUID cursor(String raw, UUID space, String state, String schedule) {
        if (raw == null) return null;
        try {
            if (!raw.matches("[A-Za-z0-9_-]{1,256}")) throw ScheduleRules.bad();
            var bytes = Base64.getUrlDecoder().decode(raw);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw)) throw ScheduleRules.bad();
            var values = new String(bytes, StandardCharsets.US_ASCII).split("\\|", -1);
            if (values.length != 4 || !values[0].equals(space.toString())
                    || !values[1].equals(state) || !values[2].equals(schedule)) throw ScheduleRules.bad();
            return ScheduleRules.id(values[3]);
        } catch (IllegalArgumentException e) { throw ScheduleRules.bad(); }
    }
}
