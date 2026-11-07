package io.knowledgebrain.content;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Content-owned reverse references. The target Concept is never resolved here. */
@RestController
@ConditionalOnProperty(name="brain.content.concepts.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/concept-references/{conceptId}/notes")
class ConceptNotesController {
    private final NoteStore notes;
    private final NoteController guard;
    private final MeterRegistry metrics;
    ConceptNotesController(NoteStore notes,NoteController guard,MeterRegistry metrics) {
        this.notes=notes;this.guard=guard;this.metrics=metrics;
    }
    @GetMapping ResponseEntity<NoteConceptStore.NotesPage> get(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request) {
        NoteStore.id(conceptId);
        var parameters=request.getParameterMap();
        if(parameters.entrySet().stream().anyMatch(p->!Set.of("after","limit").contains(p.getKey()) || p.getValue()==null || p.getValue().length!=1))throw bad();
        UUID after=null;int limit=20;
        if(parameters.containsKey("after")) {
            String value=parameters.get("after")[0];
            if(value==null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw bad();
            after=UUID.fromString(value);
        }
        if(parameters.containsKey("limit")) {
            String value=parameters.get("limit")[0];
            if(value==null || !value.matches("[1-9][0-9]{0,2}"))throw bad();
            limit=Integer.parseInt(value);if(limit>100)throw bad();
        }
        guard.authorize(spaceId,request,false,guard.parent(request));
        var result=notes.notesByConcept(spaceId,conceptId,after,limit);
        metrics.counter("brain.content.concept.reads","type","linked_notes").increment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid linked Note page");}
}
