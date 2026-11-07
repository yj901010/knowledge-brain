package io.knowledgebrain.brainquery;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/** Disposable public search candidates. Authority and canonical reads remain outside this cache. */
final class PublicSearchCache implements AutoCloseable {
    static final String PREFIX = "brain:public-search:v1:";
    static final int MAX_BYTES = 16_384, TTL_SECONDS = 5;
    static final Duration READ_BUDGET = Duration.ofMillis(125);
    record Candidate(UUID id, String kind, long version) {}
    record Page(long total, List<Candidate> items) {
        Page { items = List.copyOf(items); }
    }
    record Query(UUID space, String text, String kind, int offset, int limit) {
        Query {
            PublicProjection.id(space.toString());
            if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 128
                    || text.codePoints().anyMatch(Character::isISOControl) || offset < 0
                    || limit < 1 || limit > 50 || offset > 1000 - limit
                    || kind != null && !PublicProjection.KINDS.contains(kind)) throw PublicProjection.invalid();
        }
        String binding() {
            return PublicProjection.hash(PublicProjection.JSON.writeValueAsString(
                    Arrays.asList(space.toString(), text, kind, offset, limit)));
        }
        String key() { return PREFIX + binding(); }
        @Override public String toString() { return "PublicSearchQuery[REDACTED]"; }
    }
    interface Transport extends AutoCloseable {
        // Return at most MAX_BYTES+1 bytes, including for a corrupt/oversized stored value.
        byte[] read(String key);
        void write(String key, byte[] value, int ttlSeconds);
        @Override void close();
    }
    private final Transport transport;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final ThreadPoolExecutor workers;
    private final AtomicLong retryAfter = new AtomicLong();

    PublicSearchCache(Transport transport, MeterRegistry metrics) { this(transport, metrics, Clock.systemUTC()); }
    PublicSearchCache(Transport transport, MeterRegistry metrics, Clock clock) {
        this.transport = transport; this.metrics = metrics; this.clock = clock;
        workers = transport == null ? null : new ThreadPoolExecutor(2, 2, 5, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofVirtual().name("public-search-cache-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        if (workers != null) workers.allowCoreThreadTimeOut(true);
    }

    Page load(Query query, Supplier<Page> source) {
        if (workers == null || workers.isShutdown() || clock.millis() < retryAfter.get()) {
            count("bypass"); return source.get();
        }
        var task = new FutureTask<byte[]>(() -> transport.read(query.key()));
        long started = System.nanoTime();
        try {
            workers.execute(task);
            byte[] value = task.get(READ_BUDGET.toNanos(), TimeUnit.NANOSECONDS);
            if (value != null && value.length > 0) {
                try {
                    Page page = decode(query, value, clock.millis());
                    count("hit"); return page;
                } catch (RuntimeException invalid) { count("invalid"); }
            } else count("miss");
        } catch (RejectedExecutionException busy) { count("busy"); return source.get(); }
        catch (InterruptedException interrupted) {
            task.cancel(true); Thread.currentThread().interrupt(); count("interrupted"); return source.get();
        } catch (TimeoutException timeout) {
            task.cancel(true); failed("timeout"); return source.get();
        } catch (ExecutionException failure) { failed("error"); return source.get(); }
        finally { metrics.timer("brain.public.cache.read").record(System.nanoTime() - started, TimeUnit.NANOSECONDS); }

        Page result = source.get();
        // Encoding occurs before submission; only validated, bounded metadata reaches the worker.
        final byte[] encoded;
        try { encoded = encode(query, result, clock.millis()); }
        catch (RuntimeException invalid) { count("write_invalid"); return result; }
        if (clock.millis() >= retryAfter.get()) try {
            workers.execute(() -> {
                try { transport.write(query.key(), encoded, TTL_SECONDS); count("write"); }
                catch (RuntimeException failure) { failed("write_error"); }
            });
        } catch (RejectedExecutionException busy) { count("write_dropped"); }
        return result;
    }
    private void count(String result) { metrics.counter("brain.public.cache.requests", "outcome", result).increment(); }
    private void failed(String result) { retryAfter.accumulateAndGet(clock.millis() + 1000, Math::max); count(result); }

    static byte[] encode(Query query, Page page, long now) {
        validate(query, page);
        byte[] value = PublicProjection.JSON.writeValueAsBytes(Map.of("schema", 1, "binding", query.binding(),
                "expiresAt", now + TTL_SECONDS * 1000L, "total", page.total(), "items", page.items()));
        if (value.length > MAX_BYTES) throw PublicProjection.invalid();
        return value;
    }
    static Page decode(Query query, byte[] value, long now) {
        if (value.length == 0 || value.length > MAX_BYTES) throw PublicProjection.invalid();
        JsonNode root = PublicProjection.JSON.readTree(value);
        fields(root, Set.of("schema", "binding", "expiresAt", "total", "items"));
        if (!root.path("schema").isIntegralNumber() || !root.path("schema").canConvertToInt()
                || root.path("schema").asInt() != 1 || !root.path("binding").isString()
                || !root.path("binding").asString().equals(query.binding())) throw PublicProjection.invalid();
        long expiry = integer(root.path("expiresAt"));
        if (expiry <= now || expiry > now + TTL_SECONDS * 1000L) throw PublicProjection.invalid();
        long total = integer(root.path("total"));
        JsonNode rows = root.path("items");
        if (!rows.isArray() || rows.size() > query.limit()) throw PublicProjection.invalid();
        var items = new ArrayList<Candidate>();
        for (JsonNode row : rows) {
            fields(row, Set.of("id", "kind", "version"));
            if (!row.path("id").isString() || !row.path("kind").isString()) throw PublicProjection.invalid();
            items.add(new Candidate(PublicProjection.id(row.path("id").asString()), row.path("kind").asString(),
                    integer(row.path("version"))));
        }
        var page = new Page(total, items); validate(query, page); return page;
    }
    private static long integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw PublicProjection.invalid();
        return node.asLong();
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw PublicProjection.invalid();
        for (var field : node.properties()) if (!expected.contains(field.getKey())) throw PublicProjection.invalid();
    }
    private static void validate(Query query, Page page) {
        if (page.total() < 0 || page.items().size() > query.limit()
                || page.items().size() > Math.max(0, page.total() - query.offset())) throw PublicProjection.invalid();
        var seen = new HashSet<UUID>();
        for (var item : page.items()) {
            PublicProjection.id(item.id().toString());
            if (!seen.add(item.id()) || !PublicProjection.KINDS.contains(item.kind()) || item.version() < 1
                    || query.kind() != null && !query.kind().equals(item.kind())) throw PublicProjection.invalid();
        }
    }
    @Override public void close() {
        if (workers != null) workers.shutdownNow();
        if (transport != null) transport.close();
    }
}
