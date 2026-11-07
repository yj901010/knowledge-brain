package io.knowledgebrain.content;

import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

/** Fixtures that assert UUID ordering must not depend on wall-clock steps or same-ms randomness. */
final class AdvancingTestClock extends Clock {
    private final AtomicLong time;
    private final ZoneId zone;
    AdvancingTestClock() { this(new AtomicLong(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()), ZoneOffset.UTC); }
    private AdvancingTestClock(AtomicLong time, ZoneId zone) { this.time=time;this.zone=zone; }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { return new AdvancingTestClock(time, zone); }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
    @Override public long millis() { return time.getAndIncrement(); }
}
