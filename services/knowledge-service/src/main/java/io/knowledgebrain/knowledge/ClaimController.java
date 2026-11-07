package io.knowledgebrain.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name = "brain.knowledge.api.enabled", havingValue = "true")
@RequestMapping("/api/spaces/{spaceId}/concepts/{subjectConceptId}/claims")
class ClaimController {
    record Edit(UUID predicateConceptId, ClaimStore.ObjectValue object) {}
    private final ClaimStore store;
    private final ConceptController guard;
    private final MeterRegistry metrics;
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    ClaimController(ClaimStore store, ConceptController guard, MeterRegistry metrics) { this.store = store; this.guard = guard; this.metrics = metrics; }

    @PostMapping ResponseEntity<ClaimStore.Claim> create(@PathVariable UUID spaceId, @PathVariable UUID subjectConceptId, HttpServletRequest request) throws IOException {
        ConceptStore.id(subjectConceptId); query(request, Set.of()); String parent = guard.parent(request);
        UUID actor = guard.authorize(spaceId, request, true, parent); var edit = edit(request);
        var claim = store.create(spaceId, subjectConceptId, actor, edit.predicateConceptId(), edit.object(), parent.substring(3, 35)); changed("created");
        return ResponseEntity.status(201).header("Location", "/api/spaces/" + spaceId + "/concepts/" + subjectConceptId + "/claims/" + claim.claimId())
            .eTag(tag(claim.version())).cacheControl(CacheControl.noStore()).body(claim);
    }
    @GetMapping ResponseEntity<ClaimStore.Page> list(@PathVariable UUID spaceId, @PathVariable UUID subjectConceptId, HttpServletRequest request) {
        ConceptStore.id(subjectConceptId); query(request, Set.of("after", "limit", "state"));
        UUID after = request.getParameter("after") == null ? null : id(request.getParameter("after")); int limit = (int) number(request.getParameter("limit"), 20, 100);
        String state = Optional.ofNullable(request.getParameter("state")).orElse("ACTIVE");
        if (!Set.of("ACTIVE", "RETIRED").contains(state)) throw bad("Invalid Claim listing state");
        guard.authorize(spaceId, request, false, guard.parent(request));
        var page = "RETIRED".equals(state) ? store.retired(spaceId, subjectConceptId, after, limit) : store.list(spaceId, subjectConceptId, after, limit);
        read("RETIRED".equals(state) ? "retired_list" : "list");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }
    @GetMapping("/{claimId}") ResponseEntity<ClaimStore.Claim> get(@PathVariable UUID spaceId, @PathVariable UUID subjectConceptId, @PathVariable UUID claimId, HttpServletRequest request) {
        ids(subjectConceptId, claimId); query(request, Set.of()); guard.authorize(spaceId, request, false, guard.parent(request)); var claim = store.get(spaceId, subjectConceptId, claimId); read("get");
        return ResponseEntity.ok().eTag(tag(claim.version())).cacheControl(CacheControl.noStore()).body(claim);
    }
    @PutMapping("/{claimId}") ResponseEntity<ClaimStore.Claim> update(@PathVariable UUID spaceId, @PathVariable UUID subjectConceptId, @PathVariable UUID claimId, HttpServletRequest request) throws IOException {
        ids(subjectConceptId, claimId); query(request, Set.of()); String parent = guard.parent(request);
        UUID actor = guard.authorize(spaceId, request, true, parent); long expected = version(request); var edit = edit(request);
        var claim = store.change(spaceId, subjectConceptId, claimId, actor, expected, edit.predicateConceptId(), edit.object(), false, parent.substring(3, 35)); changed("updated");
        return ResponseEntity.ok().eTag(tag(claim.version())).cacheControl(CacheControl.noStore()).body(claim);
    }
    @DeleteMapping("/{claimId}") ResponseEntity<Void> retire(@PathVariable UUID spaceId, @PathVariable UUID subjectConceptId, @PathVariable UUID claimId, HttpServletRequest request) {
        ids(subjectConceptId, claimId); query(request, Set.of()); String parent = guard.parent(request); UUID actor = guard.authorize(spaceId, request, true, parent);
        var claim = store.change(spaceId, subjectConceptId, claimId, actor, version(request), null, null, true, parent.substring(3, 35)); changed("retired");
        return ResponseEntity.noContent().eTag(tag(claim.version())).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{claimId}/revisions") ResponseEntity<List<ClaimStore.Revision>> history(@PathVariable UUID spaceId, @PathVariable UUID subjectConceptId, @PathVariable UUID claimId, HttpServletRequest request) {
        ids(subjectConceptId, claimId); query(request, Set.of("before", "limit"));
        long before = number(request.getParameter("before"), Long.MAX_VALUE, Long.MAX_VALUE); int limit = (int) number(request.getParameter("limit"), 5, 10);
        guard.authorize(spaceId, request, false, guard.parent(request)); var rows = store.history(spaceId, subjectConceptId, claimId, before, limit); read("history");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }
    private Edit edit(HttpServletRequest request) throws IOException {
        String contentType = Optional.ofNullable(request.getContentType()).orElse("").split(";", 2)[0].trim();
        if (!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(contentType)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JSON required");
        byte[] bytes = request.getInputStream().readNBytes(16385);
        if (bytes.length > 16384) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Claim request too large");
        try {
            var node = json.readTree(bytes); var object = node.path("object");
            if(!node.isObject()||node.size()!=2||!node.path("predicateConceptId").isString()||!object.isObject()||object.size()!=2||!object.path("type").isString())throw new IllegalArgumentException();
            String type=object.path("type").asString();
            ClaimStore.ObjectValue value;
            if("CONCEPT".equals(type)){if(!object.path("conceptId").isString())throw new IllegalArgumentException();value=new ClaimStore.ObjectValue(type,id(object.path("conceptId").asString()));}
            else {if(!object.path("value").isString())throw new IllegalArgumentException();value=new ClaimStore.ObjectValue(type,null,object.path("value").asString());}
            return new Edit(id(node.path("predicateConceptId").asString()),ClaimStore.object(value));
        } catch (RuntimeException error) {
            if (error instanceof ResponseStatusException status) throw status;
            throw bad("Expected a predicate Concept and an exact typed object");
        }
    }
    private static void query(HttpServletRequest request, Set<String> allowed) { request.getParameterMap().forEach((key, values) -> { if (!allowed.contains(key) || values.length != 1) throw bad("Invalid Claim query parameters"); }); }
    private static UUID id(String value) {
        try { UUID id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IllegalArgumentException(); ConceptStore.id(id); return id; }
        catch (IllegalArgumentException error) { throw bad("Canonical Claim Concept UUIDv7 required"); }
    }
    private static long number(String value, long fallback, long maximum) {
        if (value == null) return fallback;
        if (!value.matches("[1-9][0-9]{0,18}")) throw bad("Invalid Claim page cursor or limit");
        try { long result = Long.parseLong(value); if (result > maximum) throw bad("Invalid Claim page limit"); return result; }
        catch (NumberFormatException error) { throw bad("Invalid Claim page cursor or limit"); }
    }
    private static long version(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("If-Match"));
        if (values.isEmpty()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match required");
        if (values.size() != 1 || !values.getFirst().matches("\"cl[1-9][0-9]{0,18}\"")) throw bad("Single strong Claim ETag required");
        try { return Long.parseLong(values.getFirst().substring(3, values.getFirst().length() - 1)); }
        catch (NumberFormatException error) { throw bad("Invalid Claim version"); }
    }
    private static void ids(UUID subject, UUID claim) { ConceptStore.id(subject); ConceptStore.id(claim); }
    private static String tag(long version) { return "\"cl" + version + "\""; }
    private void changed(String type) { metrics.counter("brain.knowledge.claim.changes", "type", type).increment(); }
    private void read(String type) { metrics.counter("brain.knowledge.claim.reads", "type", type).increment(); }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
