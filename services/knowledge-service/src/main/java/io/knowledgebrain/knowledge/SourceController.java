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
@ConditionalOnProperty(name="brain.knowledge.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/sources")
class SourceController {
    private final SourceStore store;
    private final ConceptController guard;
    private final MeterRegistry metrics;
    private final JsonMapper json = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    SourceController(SourceStore store, ConceptController guard, MeterRegistry metrics) { this.store = store; this.guard = guard; this.metrics = metrics; }

    @PostMapping ResponseEntity<SourceStore.Source> create(@PathVariable UUID spaceId,HttpServletRequest request) throws IOException {
        query(request,Set.of()); String parent=guard.parent(request); UUID actor=guard.authorize(spaceId,request,true,parent); var fields=edit(request);
        var result=store.create(spaceId,actor,fields.title(),fields.url(),fields.citation(),fields.licenseNote(),parent.substring(3,35)); changed("created");
        return ResponseEntity.status(201).header("Location","/api/spaces/"+spaceId+"/sources/"+result.sourceId()).eTag(tag(result.version())).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping ResponseEntity<SourceStore.Page> list(@PathVariable UUID spaceId,HttpServletRequest request) {
        query(request,Set.of("after","limit","state")); UUID after=request.getParameter("after")==null?null:id(request.getParameter("after")); int limit=(int)number(request.getParameter("limit"),20,100);
        String state=Optional.ofNullable(request.getParameter("state")).orElse("ACTIVE");if(!Set.of("ACTIVE","RETIRED").contains(state))throw bad("Invalid Source listing state");
        guard.authorize(spaceId,request,false,guard.parent(request)); var result="RETIRED".equals(state)?store.retired(spaceId,after,limit):store.list(spaceId,after,limit);
        read("RETIRED".equals(state)?"retired_list":"list"); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/{sourceId}") ResponseEntity<SourceStore.Source> get(@PathVariable UUID spaceId,@PathVariable UUID sourceId,HttpServletRequest request) {
        ConceptStore.id(sourceId); query(request,Set.of()); guard.authorize(spaceId,request,false,guard.parent(request)); var result=store.get(spaceId,sourceId); read("get");
        return ResponseEntity.ok().eTag(tag(result.version())).cacheControl(CacheControl.noStore()).body(result);
    }
    @PutMapping("/{sourceId}") ResponseEntity<SourceStore.Source> update(@PathVariable UUID spaceId,@PathVariable UUID sourceId,HttpServletRequest request) throws IOException {
        ConceptStore.id(sourceId); query(request,Set.of()); String parent=guard.parent(request); UUID actor=guard.authorize(spaceId,request,true,parent); long expected=version(request); var fields=edit(request);
        var result=store.change(spaceId,sourceId,actor,expected,fields.title(),fields.url(),fields.citation(),fields.licenseNote(),false,parent.substring(3,35)); changed("updated");
        return ResponseEntity.ok().eTag(tag(result.version())).cacheControl(CacheControl.noStore()).body(result);
    }
    @DeleteMapping("/{sourceId}") ResponseEntity<Void> retire(@PathVariable UUID spaceId,@PathVariable UUID sourceId,HttpServletRequest request) {
        ConceptStore.id(sourceId); query(request,Set.of()); String parent=guard.parent(request); UUID actor=guard.authorize(spaceId,request,true,parent);
        var result=store.change(spaceId,sourceId,actor,version(request),null,null,null,null,true,parent.substring(3,35)); changed("retired");
        return ResponseEntity.noContent().eTag(tag(result.version())).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{sourceId}/revisions") ResponseEntity<List<SourceStore.Revision>> history(@PathVariable UUID spaceId,@PathVariable UUID sourceId,HttpServletRequest request) {
        ConceptStore.id(sourceId); query(request,Set.of("before","limit")); long before=number(request.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE); int limit=(int)number(request.getParameter("limit"),5,10);
        guard.authorize(spaceId,request,false,guard.parent(request)); var result=store.history(spaceId,sourceId,before,limit); read("history"); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private SourceStore.Fields edit(HttpServletRequest request) throws IOException {
        if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
        byte[] bytes=request.getInputStream().readNBytes(32769); if(bytes.length>32768)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Source request too large");
        try {
            var node=json.readTree(bytes);
            if(!node.isObject()||node.size()!=4||!node.path("title").isString()||!node.has("url")||(!node.path("url").isNull()&&!node.path("url").isString())||!node.path("citation").isString()||!node.path("licenseNote").isString())throw new IllegalArgumentException();
            return SourceStore.fields(node.path("title").asString(),node.path("url").isNull()?null:node.path("url").asString(),node.path("citation").asString(),node.path("licenseNote").asString());
        }catch(RuntimeException error){if(error instanceof ResponseStatusException status)throw status;throw bad("Expected title, URL, citation and license note");}
    }
    private static UUID id(String value){try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IllegalArgumentException();ConceptStore.id(result);return result;}catch(IllegalArgumentException error){throw bad("Canonical Source UUIDv7 required");}}
    private static void query(HttpServletRequest request,Set<String> allowed){request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1)throw bad("Invalid Source query");});}
    private static long number(String value,long fallback,long maximum){if(value==null)return fallback;if(!value.matches("[1-9][0-9]{0,18}"))throw bad("Invalid Source page cursor or limit");try{long result=Long.parseLong(value);if(result>maximum)throw bad("Invalid Source page limit");return result;}catch(NumberFormatException error){throw bad("Invalid Source page cursor or limit");}}
    private static long version(HttpServletRequest request){var values=Collections.list(request.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");if(values.size()!=1||!values.getFirst().matches("\"src[1-9][0-9]{0,18}\""))throw bad("Single strong Source ETag required");try{return Long.parseLong(values.getFirst().substring(4,values.getFirst().length()-1));}catch(NumberFormatException error){throw bad("Invalid Source version");}}
    private static String tag(long version){return "\"src"+version+"\"";}
    private void changed(String type){metrics.counter("brain.knowledge.source.changes","type",type).increment();}
    private void read(String type){metrics.counter("brain.knowledge.source.reads","type",type).increment();}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
