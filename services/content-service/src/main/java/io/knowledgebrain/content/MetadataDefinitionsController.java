package io.knowledgebrain.content;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@ConditionalOnProperty(name="brain.content.api.enabled",havingValue="true")
class MetadataDefinitionsController {
    private final NoteStore notes;private final NoteController guard;private final MeterRegistry metrics;
    MetadataDefinitionsController(NoteStore notes,NoteController guard,MeterRegistry metrics) {this.notes=notes;this.guard=guard;this.metrics=metrics;}
    @GetMapping({"/api/spaces/{spaceId}/tags","/api/spaces/{spaceId}/property-definitions"}) ResponseEntity<NoteMetadataStore.Page> list(@PathVariable UUID spaceId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        pageQuery(after,limit,request,false,true);String q=request.getParameter("q");if(q!=null)q=MetadataNameQuery.parse(q);guard.checkMetadataAccess(spaceId,request);boolean tags=request.getRequestURI().endsWith("/tags");var result=q==null?notes.definitions(spaceId,tags,after,limit):notes.definitions(spaceId,tags,after,limit,q);
        metrics.counter("brain.content.metadata.reads","type",(tags?"tags":"property_definitions")+(q==null?"":"_filtered")).increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/api/spaces/{spaceId}/tags/{tagId}/notes") ResponseEntity<NoteStore.Page> notes(@PathVariable UUID spaceId,@PathVariable UUID tagId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        NoteStore.id(tagId);pageQuery(after,limit,request);guard.checkMetadataAccess(spaceId,request);var result=notes.tagNotes(spaceId,tagId,after,limit);
        metrics.counter("brain.content.metadata.reads","type","tag_notes").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/api/spaces/{spaceId}/property-definitions/{propertyId}/notes") ResponseEntity<NoteMetadataStore.PropertyNotesPage> propertyNotes(@PathVariable UUID spaceId,@PathVariable UUID propertyId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        NoteStore.id(propertyId);pageQuery(after,limit,request,true);String value=request.getParameter("value"),min=request.getParameter("minValue"),max=request.getParameter("maxValue");PropertyValueFilter.selection(value,min,max);UUID tagId=null;if(request.getParameter("tagId")!=null){try{String raw=request.getParameter("tagId");tagId=UUID.fromString(raw);NoteStore.id(tagId);if(!tagId.toString().equals(raw))throw new IllegalArgumentException();}catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid tag condition");}}guard.checkMetadataAccess(spaceId,request);var result=tagId!=null?notes.propertyNotes(spaceId,propertyId,after,limit,value,min,max,tagId):min!=null||max!=null?notes.propertyNotes(spaceId,propertyId,after,limit,value,min,max):value==null?notes.propertyNotes(spaceId,propertyId,after,limit):notes.propertyNotes(spaceId,propertyId,after,limit,value);
        metrics.counter("brain.content.metadata.reads","type",value==null&&min==null&&max==null&&tagId==null?"property_notes":"property_notes_filtered").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private static void pageQuery(UUID after,int limit,HttpServletRequest request) {pageQuery(after,limit,request,false);}
    private static void pageQuery(UUID after,int limit,HttpServletRequest request,boolean filter) {pageQuery(after,limit,request,filter,false);}
    private static void pageQuery(UUID after,int limit,HttpServletRequest request,boolean filter,boolean catalog) {
        request.getParameterMap().forEach((key,values)->{if(!(Set.of("after","limit").contains(key)||catalog&&key.equals("q")||filter&&Set.of("value","minValue","maxValue","tagId").contains(key))||values.length!=1||values[0]==null||values[0].isEmpty()&&!(filter&&key.equals("value")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid metadata query parameters");});
        if(after!=null)NoteStore.id(after);if(limit<1 || limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page limit");
    }
}
