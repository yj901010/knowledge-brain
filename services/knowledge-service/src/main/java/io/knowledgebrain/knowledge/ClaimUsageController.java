package io.knowledgebrain.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="brain.knowledge.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/concepts/{conceptId}/claim-usages")
class ClaimUsageController {
    private final ClaimStore store;
    private final ConceptController guard;
    private final MeterRegistry metrics;
    ClaimUsageController(ClaimStore store,ConceptController guard,MeterRegistry metrics){this.store=store;this.guard=guard;this.metrics=metrics;}
    @GetMapping ResponseEntity<ClaimStore.UsagePage> list(@PathVariable String spaceId,@PathVariable String conceptId,HttpServletRequest request)throws IOException {
        UUID space=id(spaceId),concept=id(conceptId);
        request.getParameterMap().forEach((key,values)->{if(!Set.of("role","after","limit").contains(key)||values.length!=1)throw bad("Invalid Claim usage query");});
        ClaimStore.UsageRole role=role(request.getParameter("role"));
        UUID after=request.getParameter("after")==null?null:id(request.getParameter("after"));
        int limit=limit(request.getParameter("limit"));
        guard.authorize(space,request,false,guard.parent(request));
        if(request.getInputStream().read()!=-1)throw bad("Claim usage reads accept no body");
        var page=store.usages(space,concept,role,after,limit);
        metrics.counter("brain.knowledge.claim.reads","type","usage-"+role.name().toLowerCase(Locale.ROOT)).increment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }
    private static ClaimStore.UsageRole role(String value){if(value==null)return ClaimStore.UsageRole.OBJECT;try{return ClaimStore.UsageRole.valueOf(value);}catch(IllegalArgumentException error){throw bad("OBJECT or PREDICATE usage role required");}}
    private static UUID id(String value){try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IllegalArgumentException();ConceptStore.id(result);return result;}catch(IllegalArgumentException error){throw bad("Canonical UUIDv7 required");}}
    private static int limit(String value){if(value==null)return 20;if(!value.matches("[1-9][0-9]{0,2}"))throw bad("Invalid Claim usage page limit");int result=Integer.parseInt(value);if(result>100)throw bad("Invalid Claim usage page limit");return result;}
    private static ResponseStatusException bad(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
}
