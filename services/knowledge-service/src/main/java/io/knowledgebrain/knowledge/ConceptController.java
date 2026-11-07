package io.knowledgebrain.knowledge;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
@RestController
@ConditionalOnProperty(name="brain.knowledge.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/concepts")
class ConceptController {
    record Edit(String title,String description) {}
    private final ConceptStore notes;private final SpaceAuthorizationClient space;private final MeterRegistry metrics;private final String origin;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();private final SecureRandom random=new SecureRandom();
    ConceptController(ConceptStore notes,SpaceAuthorizationClient space,MeterRegistry metrics,@Value("${brain.knowledge.browser-origin}") String origin) { this.notes=notes;this.space=space;this.metrics=metrics;this.origin=origin; }
    @PostMapping ResponseEntity<ConceptStore.Concept> create(@PathVariable UUID spaceId,HttpServletRequest request) throws java.io.IOException {
        query(request,Set.of());String parent=parent(request);UUID actor=authorize(spaceId,request,true,parent);var edit=edit(request);
        var note=notes.create(spaceId,actor,edit.title(),edit.description(),parent.substring(3,35));metrics.counter("brain.knowledge.concept.changes","type","created").increment();
        return ResponseEntity.status(201).header("Location","/api/spaces/"+spaceId+"/concepts/"+note.conceptId()).eTag(tag(note.version())).cacheControl(CacheControl.noStore()).body(note);
    }
    @GetMapping("/{conceptId}") ResponseEntity<ConceptStore.Concept> get(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request) {
        query(request,Set.of());ConceptStore.id(conceptId);authorize(spaceId,request,false,parent(request));var note=notes.get(spaceId,conceptId);return ResponseEntity.ok().eTag(tag(note.version())).cacheControl(CacheControl.noStore()).body(note);
    }
    @GetMapping ResponseEntity<ConceptStore.Page> list(@PathVariable UUID spaceId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request) {
        query(request,Set.of("after","limit","state"));if(after!=null)ConceptStore.id(after);limit(limit,100);
        String state=Optional.ofNullable(request.getParameter("state")).orElse("ACTIVE");if(!Set.of("ACTIVE","RETIRED").contains(state))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Concept listing state");
        authorize(spaceId,request,false,parent(request));var page="RETIRED".equals(state)?notes.retired(spaceId,after,limit):notes.list(spaceId,after,limit);
        metrics.counter("brain.knowledge.concept.reads","type","RETIRED".equals(state)?"retired_list":"list").increment();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }
    @PutMapping("/{conceptId}") ResponseEntity<ConceptStore.Concept> update(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request) throws java.io.IOException {
        query(request,Set.of());ConceptStore.id(conceptId);String parent=parent(request);UUID actor=authorize(spaceId,request,true,parent);long version=version(request);var edit=edit(request);
        var note=notes.change(spaceId,conceptId,actor,version,edit.title(),edit.description(),false,parent.substring(3,35));metrics.counter("brain.knowledge.concept.changes","type","updated").increment();
        return ResponseEntity.ok().eTag(tag(note.version())).cacheControl(CacheControl.noStore()).body(note);
    }
    @DeleteMapping("/{conceptId}") ResponseEntity<Void> retire(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request) {
        query(request,Set.of());ConceptStore.id(conceptId);String parent=parent(request);UUID actor=authorize(spaceId,request,true,parent);
        var note=notes.change(spaceId,conceptId,actor,version(request),null,null,true,parent.substring(3,35));metrics.counter("brain.knowledge.concept.changes","type","retired").increment();
        return ResponseEntity.noContent().eTag(tag(note.version())).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{conceptId}/revisions") ResponseEntity<List<ConceptStore.Revision>> history(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="5") int limit,HttpServletRequest request) {
        query(request,Set.of("before","limit"));ConceptStore.id(conceptId);limit(limit,10);if(before<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid revision cursor");authorize(spaceId,request,false,parent(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.history(spaceId,conceptId,before,limit));
    }
    UUID authorize(UUID spaceId,HttpServletRequest request,boolean write,String parent) {
        ConceptStore.id(spaceId);if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        if(write && (Collections.list(request.getHeaders("Origin")).size()!=1 || !origin.equals(request.getHeader("Origin")) || (request.getHeader("Sec-Fetch-Site")!=null && !"same-origin".equals(request.getHeader("Sec-Fetch-Site")))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
        String token=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if("__Host-brain_guest".equals(cookie.getName())) { if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential");token=cookie.getValue(); }
        return space.authorize(spaceId,token,write,parent);
    }
    private Edit edit(HttpServletRequest request) throws java.io.IOException {
        if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
        byte[] bytes=request.getInputStream().readNBytes(262145);if(bytes.length>262144)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
        try { var node=json.readTree(bytes);if(!node.isObject() || node.size()!=2 || !node.path("title").isString() || !node.path("description").isString())throw new IllegalArgumentException();return new Edit(node.path("title").asString(),node.path("description").asString()); }
        catch(RuntimeException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected title and description strings"); }
    }
    private static long version(HttpServletRequest request) {
        var headers=Collections.list(request.getHeaders("If-Match"));if(headers.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");
        if(headers.size()!=1 || !headers.getFirst().matches("\"[1-9][0-9]{0,18}\""))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Single strong version ETag required");
        try { return Long.parseLong(headers.getFirst().substring(1,headers.getFirst().length()-1)); } catch(NumberFormatException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid version"); }
    }
    private static void query(HttpServletRequest request,Set<String> allowed) {
        request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Concept query parameters");});
    }
    private static String tag(long version) { return "\""+version+"\""; }
    private static void limit(int value,int max) { if(value<1 || value>max)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page limit"); }
    String parent(HttpServletRequest request) {
        String value=request.getHeader("traceparent");if(value!=null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") && !value.substring(3,35).equals("0".repeat(32)) && !value.substring(36,52).equals("0".repeat(16)))return value;
        byte[] trace=new byte[16],span=new byte[8];random.nextBytes(trace);random.nextBytes(span);return "00-"+HexFormat.of().formatHex(trace)+"-"+HexFormat.of().formatHex(span)+"-01";
    }
}
