package io.knowledgebrain.brainquery;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublicSearchCacheTest {
    static final UUID SPACE = UUID.fromString(PublicProjectionTest.SPACE), ID = UUID.fromString(PublicProjectionTest.ID);
    static final PublicSearchCache.Query QUERY = new PublicSearchCache.Query(SPACE, "private-looking search text", null, 0, 20);
    static final PublicSearchCache.Page PAGE = new PublicSearchCache.Page(1,
            List.of(new PublicSearchCache.Candidate(ID, "CONCEPT", 2)));
    static class Memory implements PublicSearchCache.Transport {
        volatile byte[] value; AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        final CountDownLatch written = new CountDownLatch(1);
        public byte[] read(String key) { reads.incrementAndGet(); return value; }
        public void write(String key, byte[] data, int ttl) {
            assertThat(key).matches("brain:public-search:v1:[0-9a-f]{64}"); assertThat(ttl).isEqualTo(5);
            value = data; writes.incrementAndGet(); written.countDown();
        }
        public void close() {}
    }
    @Test void boundedMetadataOnlyAndEveryQueryDimensionPartitionsKeys() {
        byte[] bytes = PublicSearchCache.encode(QUERY, PAGE, 1000);
        assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(QUERY.text(), "title", "content", "actor", "token");
        assertThat(QUERY.toString()).doesNotContain(QUERY.text());
        assertThat(PublicSearchCache.decode(QUERY, bytes, 1001)).isEqualTo(PAGE);
        var keys = new HashSet<String>(); keys.add(QUERY.key());
        keys.add(new PublicSearchCache.Query(ID, QUERY.text(), null, 0, 20).key());
        keys.add(new PublicSearchCache.Query(SPACE, QUERY.text()+"x", null, 0, 20).key());
        keys.add(new PublicSearchCache.Query(SPACE, QUERY.text(), "CONCEPT", 0, 20).key());
        keys.add(new PublicSearchCache.Query(SPACE, QUERY.text(), null, 1, 20).key());
        keys.add(new PublicSearchCache.Query(SPACE, QUERY.text(), null, 0, 19).key());
        assertThat(keys).hasSize(6);
    }
    @Test void corruptCrossScopeExpiredAndAmbiguousJsonAreRejected() {
        String valid = new String(PublicSearchCache.encode(QUERY, PAGE, 1000), java.nio.charset.StandardCharsets.UTF_8);
        var wrongSpace = new PublicSearchCache.Query(ID, QUERY.text(), null, 0, 20);
        assertThatThrownBy(()->PublicSearchCache.decode(wrongSpace, valid.getBytes(), 1000)).isInstanceOf(RuntimeException.class);
        for (String corrupt : List.of(valid.replace("\"schema\":1", "\"schema\":2"),
                valid.replace("\"schema\":1", "\"schema\":1,\"schema\":1"),
                valid.replace("\"total\":1", "\"total\":9223372036854775808"),
                valid.replace("\"total\":1", "\"total\":0"),
                valid.replace("CONCEPT", "PRIVATE_NOTE"), valid.replace("\"version\":2", "\"version\":0"),
                valid.replace(ID.toString(), "019a0000-0000-4000-8000-000000000001"),
                valid.replace("\"expiresAt\":6000", "\"expiresAt\":6001"), valid+" {}",
                valid.substring(0, valid.length()-1)+",\"content\":\"extra\"}"))
            assertThatThrownBy(()->PublicSearchCache.decode(QUERY, corrupt.getBytes(), 1000)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->PublicSearchCache.decode(QUERY, valid.getBytes(), 6000)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->PublicSearchCache.decode(QUERY, new byte[PublicSearchCache.MAX_BYTES+1], 1000)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->PublicSearchCache.encode(QUERY, new PublicSearchCache.Page(2, List.of(PAGE.items().getFirst(), PAGE.items().getFirst())), 1000)).isInstanceOf(RuntimeException.class);
        var onlyClaims = new PublicSearchCache.Query(SPACE, QUERY.text(), "CLAIM", 0, 20);
        assertThatThrownBy(()->PublicSearchCache.encode(onlyClaims, PAGE, 1000)).isInstanceOf(RuntimeException.class);
    }
    @Test void missWritesWithTtlAndNextHitSkipsOnlySearchSource() throws Exception {
        var transport = new Memory(); var registry = new SimpleMeterRegistry(); var sources = new AtomicInteger();
        try (var cache = new PublicSearchCache(transport, registry)) {
            assertThat(cache.load(QUERY, ()->{sources.incrementAndGet(); return PAGE;})).isEqualTo(PAGE);
            assertThat(transport.written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.load(QUERY, ()->{throw new AssertionError("Source was queried on hit");})).isEqualTo(PAGE);
            assertThat(sources).hasValue(1); assertThat(transport.reads).hasValue(2);
            assertThat(registry.get("brain.public.cache.requests").tag("outcome", "hit").counter().count()).isEqualTo(1);
        }
    }
    @Test void invalidPayloadFallsBackAndReplacesOnlyThisEntry() throws Exception {
        var transport = new Memory(); transport.value = "{}".getBytes();
        try (var cache = new PublicSearchCache(transport, new SimpleMeterRegistry())) {
            assertThat(cache.load(QUERY, ()->PAGE)).isEqualTo(PAGE);
            assertThat(transport.written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(PublicSearchCache.decode(QUERY, transport.value, System.currentTimeMillis())).isEqualTo(PAGE);
        }
    }
    @Test void sourceFailureIsNotCachedAndDisabledCacheNeverChangesSourceBehavior() {
        var transport = new Memory();
        try (var cache = new PublicSearchCache(transport, new SimpleMeterRegistry());
             var disabled = new PublicSearchCache(null, new SimpleMeterRegistry())) {
            assertThatThrownBy(()->cache.load(QUERY, ()->{throw new NoteSearchClient.Unavailable();})).isInstanceOf(NoteSearchClient.Unavailable.class);
            assertThat(transport.writes).hasValue(0);
            assertThat(disabled.load(QUERY, ()->PAGE)).isEqualTo(PAGE);
            assertThatThrownBy(()->disabled.load(QUERY, ()->{throw new NoteSearchClient.Unavailable();})).isInstanceOf(NoteSearchClient.Unavailable.class);
        }
    }
    @Test void transportFailureHasCooldownAndRecoversWithoutRestart() throws Exception {
        var now = new AtomicLong(1000); var transport = new Memory() {
            @Override public byte[] read(String key) { if (reads.incrementAndGet()==1) throw new IllegalStateException("offline"); return value; }
        };
        Clock clock = new Clock() {public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;}
            public Instant instant(){return Instant.ofEpochMilli(now.get());} public long millis(){return now.get();}};
        try (var cache = new PublicSearchCache(transport, new SimpleMeterRegistry(), clock)) {
            assertThat(cache.load(QUERY, ()->PAGE)).isEqualTo(PAGE); assertThat(transport.writes).hasValue(0);
            assertThat(cache.load(QUERY, ()->PAGE)).isEqualTo(PAGE); assertThat(transport.reads).hasValue(1);
            now.set(2001); assertThat(cache.load(QUERY, ()->PAGE)).isEqualTo(PAGE);
            assertThat(transport.written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cache.load(QUERY, ()->{throw new AssertionError();})).isEqualTo(PAGE);
        }
    }
    @Test void stalledReadsHaveCallerDeadlineAndBoundedWorkersWithoutQueue() throws Exception {
        var release = new CountDownLatch(1); var active = new AtomicInteger(); var peak = new AtomicInteger();
        var transport = new Memory() { @Override public byte[] read(String key) {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { while (release.getCount()>0) try { release.await(); } catch (InterruptedException ignored) {} }
            finally { active.decrementAndGet(); } return null;
        }};
        var registry = new SimpleMeterRegistry();
        try (var cache = new PublicSearchCache(transport, registry)) {
            long started = System.nanoTime(); assertThat(cache.load(QUERY, ()->PAGE)).isEqualTo(PAGE);
            assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(1));
            for (int i=0;i<100;i++) assertThat(cache.load(QUERY, ()->PAGE)).isEqualTo(PAGE);
            assertThat(peak.get()).isLessThanOrEqualTo(2);
            assertThat(registry.get("brain.public.cache.requests").tag("outcome", "timeout").counter().count()).isEqualTo(1);
        } finally { release.countDown(); }
    }
}
