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
@RequestMapping("/api/spaces/{spaceId}/sources/{sourceId}/citations")
class SourceCitationController {
    private final CitationStore store;
    private final ConceptController guard;
    private final MeterRegistry metrics;
    SourceCitationController(CitationStore store,ConceptController guard,MeterRegistry metrics){this.store=store;this.guard=guard;this.metrics=metrics;}
    @GetMapping ResponseEntity<CitationStore.SourcePage> listPath(@PathVariable String spaceId,@PathVariable String sourceId,HttpServletRequest request)throws IOException {
        return list(id(spaceId),id(sourceId),request);
    }
    ResponseEntity<CitationStore.SourcePage> list(UUID spaceId,UUID sourceId,HttpServletRequest request)throws IOException {
        ConceptStore.id(sourceId);
        request.getParameterMap().forEach((key,values)->{if(!Set.of("after","limit").contains(key)||values.length!=1)throw bad("Invalid Source citation query");});
        UUID after=request.getParameter("after")==null?null:id(request.getParameter("after"));int limit=limit(request.getParameter("limit"));
        guard.authorize(spaceId,request,false,guard.parent(request));
        if(request.getInputStream().read()!=-1)throw bad("Source citation reads accept no body");
        var result=store.bySource(spaceId,sourceId,after,limit);metrics.counter("brain.knowledge.citation.reads","type","source").increment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private static UUID id(String value){try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IllegalArgumentException();ConceptStore.id(result);return result;}catch(IllegalArgumentException error){throw bad("Canonical UUIDv7 required");}}
    private static int limit(String value){if(value==null)return 20;if(!value.matches("[1-9][0-9]{0,2}"))throw bad("Invalid Source citation page limit");int result=Integer.parseInt(value);if(result>100)throw bad("Invalid Source citation page limit");return result;}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
