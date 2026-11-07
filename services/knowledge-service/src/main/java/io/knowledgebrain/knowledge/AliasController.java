package io.knowledgebrain.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = "brain.knowledge.api.enabled", havingValue = "true")
@RequestMapping("/api/spaces/{spaceId}/concepts/{conceptId}/aliases")
class AliasController {
    private final AliasStore store;
    private final ConceptController guard;
    private final MeterRegistry metrics;
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    AliasController(AliasStore store, ConceptController guard, MeterRegistry metrics) { this.store = store; this.guard = guard; this.metrics = metrics; }

    @PostMapping ResponseEntity<AliasStore.Alias> create(@PathVariable UUID spaceId, @PathVariable UUID conceptId, HttpServletRequest request) throws IOException {
        ConceptStore.id(conceptId); query(request, Set.of()); String parent = guard.parent(request);
        UUID actor = guard.authorize(spaceId, request, true, parent);
        var alias = store.create(spaceId, conceptId, actor, edit(request), parent.substring(3, 35));
        changed("created");
        return ResponseEntity.status(201).header("Location", "/api/spaces/" + spaceId + "/concepts/" + conceptId + "/aliases/" + alias.aliasId())
            .eTag(tag(alias.version())).cacheControl(CacheControl.noStore()).body(alias);
    }

    @GetMapping ResponseEntity<AliasStore.Page> list(@PathVariable UUID spaceId, @PathVariable UUID conceptId, HttpServletRequest request) {
        ConceptStore.id(conceptId); query(request, Set.of("after", "limit", "state"));
        UUID after = cursor(request.getParameter("after")); int limit = (int) number(request.getParameter("limit"), 20, 100);
        String state = Optional.ofNullable(request.getParameter("state")).orElse("ACTIVE");
        if (!Set.of("ACTIVE", "RETIRED").contains(state)) throw bad("Invalid Alias state");
        guard.authorize(spaceId, request, false, guard.parent(request));
        var page = "RETIRED".equals(state) ? store.retired(spaceId, conceptId, after, limit) : store.list(spaceId, conceptId, after, limit);
        read("RETIRED".equals(state) ? "retired_list" : "list");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/{aliasId}") ResponseEntity<AliasStore.Alias> get(@PathVariable UUID spaceId, @PathVariable UUID conceptId, @PathVariable UUID aliasId, HttpServletRequest request) {
        ids(conceptId, aliasId); query(request, Set.of()); guard.authorize(spaceId, request, false, guard.parent(request));
        var alias = store.get(spaceId, conceptId, aliasId); read("get");
        return ResponseEntity.ok().eTag(tag(alias.version())).cacheControl(CacheControl.noStore()).body(alias);
    }

    @PutMapping("/{aliasId}") ResponseEntity<AliasStore.Alias> update(@PathVariable UUID spaceId, @PathVariable UUID conceptId, @PathVariable UUID aliasId, HttpServletRequest request) throws IOException {
        ids(conceptId, aliasId); query(request, Set.of()); String parent = guard.parent(request);
        UUID actor = guard.authorize(spaceId, request, true, parent); long expected = version(request);
        var alias = store.change(spaceId, conceptId, aliasId, actor, expected, edit(request), false, parent.substring(3, 35)); changed("updated");
        return ResponseEntity.ok().eTag(tag(alias.version())).cacheControl(CacheControl.noStore()).body(alias);
    }

    @DeleteMapping("/{aliasId}") ResponseEntity<Void> retire(@PathVariable UUID spaceId, @PathVariable UUID conceptId, @PathVariable UUID aliasId, HttpServletRequest request) {
        ids(conceptId, aliasId); query(request, Set.of()); String parent = guard.parent(request);
        UUID actor = guard.authorize(spaceId, request, true, parent);
        var alias = store.change(spaceId, conceptId, aliasId, actor, version(request), null, true, parent.substring(3, 35)); changed("retired");
        return ResponseEntity.noContent().eTag(tag(alias.version())).cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/{aliasId}/revisions") ResponseEntity<List<AliasStore.Revision>> history(@PathVariable UUID spaceId, @PathVariable UUID conceptId, @PathVariable UUID aliasId, HttpServletRequest request) {
        ids(conceptId, aliasId); query(request, Set.of("before", "limit"));
        long before = number(request.getParameter("before"), Long.MAX_VALUE, Long.MAX_VALUE);
        int limit = (int) number(request.getParameter("limit"), 5, 10);
        guard.authorize(spaceId, request, false, guard.parent(request)); read("history");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(spaceId, conceptId, aliasId, before, limit));
    }

    private String edit(HttpServletRequest request) throws IOException {
        String contentType = Optional.ofNullable(request.getContentType()).orElse("").split(";", 2)[0].trim();
        if (!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(contentType)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JSON required");
        byte[] bytes = request.getInputStream().readNBytes(4097);
        if (bytes.length > 4096) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Alias request too large");
        try {
            var node = json.readTree(bytes);
            if (!node.isObject() || node.size() != 1 || !node.path("label").isString()) throw new IllegalArgumentException();
            return AliasStore.label(node.path("label").asString());
        } catch (RuntimeException error) {
            if (error instanceof ResponseStatusException status) throw status;
            throw bad("Expected an Alias label string");
        }
    }

    private static void query(HttpServletRequest request, Set<String> allowed) {
        request.getParameterMap().forEach((key, values) -> { if (!allowed.contains(key) || values.length != 1) throw bad("Invalid Alias query parameters"); });
    }
    private static UUID cursor(String value) {
        if (value == null) return null;
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IllegalArgumentException(); ConceptStore.id(id); return id; }
        catch (IllegalArgumentException error) { throw bad("Invalid Alias cursor"); }
    }
    private static long number(String value, long fallback, long maximum) {
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,18}")) throw bad("Invalid Alias page cursor or limit");
        try { long result = Long.parseLong(value); if (result > maximum) throw bad("Invalid Alias page limit"); return result; }
        catch (NumberFormatException error) { throw bad("Invalid Alias page cursor or limit"); }
    }
    private static long version(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("If-Match"));
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match required");
        if (values.size() != 1 || !values.getFirst().matches("\"a[1-9][0-9]{0,18}\"")) throw bad("Single strong Alias ETag required");
        try { return Long.parseLong(values.getFirst().substring(2, values.getFirst().length() - 1)); }
        catch (NumberFormatException error) { throw bad("Invalid Alias version"); }
    }
    private static void ids(UUID concept, UUID alias) { ConceptStore.id(concept); ConceptStore.id(alias); }
    private static String tag(long version) { return "\"a" + version + "\""; }
    private void changed(String type) { metrics.counter("brain.knowledge.alias.changes", "type", type).increment(); }
    private void read(String type) { metrics.counter("brain.knowledge.alias.reads", "type", type).increment(); }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
