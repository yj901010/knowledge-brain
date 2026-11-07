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
@RequestMapping("/api/spaces/{spaceId}/concepts/{conceptId}/external-mappings")
class ExternalMappingController {
    private final ExternalMappingStore store;
    private final ConceptController guard;
    private final MeterRegistry metrics;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    ExternalMappingController(ExternalMappingStore store,ConceptController guard,MeterRegistry metrics){this.store=store;this.guard=guard;this.metrics=metrics;}
    @PostMapping ResponseEntity<ExternalMappingStore.Mapping> create(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request)throws IOException {
        ConceptStore.id(conceptId);query(request,Set.of());String parent=guard.parent(request);UUID actor=guard.authorize(spaceId,request,true,parent);var value=edit(request);
        var result=store.create(spaceId,conceptId,actor,value,parent.substring(3,35));changed("created");
        return ResponseEntity.status(201).header("Location","/api/spaces/"+spaceId+"/concepts/"+conceptId+"/external-mappings/"+result.mappingId()).eTag(tag(result.version())).cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping ResponseEntity<ExternalMappingStore.Page> list(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request) {
        ConceptStore.id(conceptId);query(request,Set.of("after","limit","state"));UUID after=request.getParameter("after")==null?null:id(request.getParameter("after"));int limit=(int)number(request.getParameter("limit"),20,100);
        String state=Optional.ofNullable(request.getParameter("state")).orElse("ACTIVE");if(!Set.of("ACTIVE","RETIRED").contains(state))throw bad("Invalid mapping state filter");
        guard.authorize(spaceId,request,false,guard.parent(request));var result="RETIRED".equals(state)?store.retired(spaceId,conceptId,after,limit):store.list(spaceId,conceptId,after,limit);read("RETIRED".equals(state)?"retired_list":"list");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/{mappingId}") ResponseEntity<ExternalMappingStore.Mapping> get(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@PathVariable UUID mappingId,HttpServletRequest request) {
        ConceptStore.id(conceptId);ConceptStore.id(mappingId);query(request,Set.of());guard.authorize(spaceId,request,false,guard.parent(request));var result=store.get(spaceId,conceptId,mappingId);read("get");
        return ResponseEntity.ok().eTag(tag(result.version())).cacheControl(CacheControl.noStore()).body(result);
    }
    @PutMapping("/{mappingId}") ResponseEntity<ExternalMappingStore.Mapping> update(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@PathVariable UUID mappingId,HttpServletRequest request)throws IOException {
        ConceptStore.id(conceptId);ConceptStore.id(mappingId);query(request,Set.of());String parent=guard.parent(request);UUID actor=guard.authorize(spaceId,request,true,parent);long expected=version(request);var value=edit(request);
        var result=store.change(spaceId,conceptId,mappingId,actor,expected,value,false,parent.substring(3,35));changed("updated");return ResponseEntity.ok().eTag(tag(result.version())).cacheControl(CacheControl.noStore()).body(result);
    }
    @DeleteMapping("/{mappingId}") ResponseEntity<Void> retire(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@PathVariable UUID mappingId,HttpServletRequest request)throws IOException {
        ConceptStore.id(conceptId);ConceptStore.id(mappingId);query(request,Set.of());String parent=guard.parent(request);UUID actor=guard.authorize(spaceId,request,true,parent);
        if(request.getInputStream().read()!=-1)throw bad("Mapping retirement accepts no body");
        var result=store.change(spaceId,conceptId,mappingId,actor,version(request),null,true,parent.substring(3,35));changed("retired");return ResponseEntity.noContent().eTag(tag(result.version())).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/{mappingId}/revisions") ResponseEntity<List<ExternalMappingStore.Revision>> history(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@PathVariable UUID mappingId,HttpServletRequest request) {
        ConceptStore.id(conceptId);ConceptStore.id(mappingId);query(request,Set.of("before","limit"));long before=number(request.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)number(request.getParameter("limit"),5,10);
        guard.authorize(spaceId,request,false,guard.parent(request));var result=store.history(spaceId,conceptId,mappingId,before,limit);read("history");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    private ExternalMappingStore.Edit edit(HttpServletRequest request)throws IOException {
        if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
        byte[] bytes=request.getInputStream().readNBytes(16385);if(bytes.length>16384)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Mapping request too large");
        try{
            var node=json.readTree(bytes);
            if(!node.isObject()||node.size()!=7)throw new IllegalArgumentException();
            for(String field:List.of("system","code","systemVersion","label","sourceId"))if(!node.path(field).isString())throw new IllegalArgumentException();
            return ExternalMappingStore.edit(node.path("system").asString(),node.path("code").asString(),node.path("systemVersion").asString(),node.path("label").asString(),id(node.path("sourceId").asString()),integer(node.path("sourceVersion")),integer(node.path("conceptVersion")));
        }catch(RuntimeException error){if(error instanceof ResponseStatusException status)throw status;throw bad("Expected system, code, systemVersion, label, sourceId, sourceVersion and conceptVersion");}
    }

    private static long integer(tools.jackson.databind.JsonNode value){if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<1)throw bad("Positive integral version required");return value.asLong();}
    private static UUID id(String value){try{UUID result=UUID.fromString(value);if(!result.toString().equals(value))throw new IllegalArgumentException();ConceptStore.id(result);return result;}catch(IllegalArgumentException error){throw bad("Canonical mapping UUIDv7 required");}}
    private static void query(HttpServletRequest request,Set<String> allowed){request.getParameterMap().forEach((key,values)->{if(!allowed.contains(key)||values.length!=1)throw bad("Invalid mapping query");});}
    private static long number(String value,long fallback,long maximum){if(value==null)return fallback;if(!value.matches("[1-9][0-9]{0,18}"))throw bad("Invalid mapping page cursor or limit");try{long result=Long.parseLong(value);if(result>maximum)throw bad("Invalid mapping page limit");return result;}catch(NumberFormatException error){throw bad("Invalid mapping page cursor or limit");}}
    private static long version(HttpServletRequest request){var values=Collections.list(request.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");if(values.size()!=1||!values.getFirst().matches("\"xm[1-9][0-9]{0,18}\""))throw bad("Single strong mapping ETag required");try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException error){throw bad("Invalid mapping version");}}
    private static String tag(long version){return "\"xm"+version+"\"";}
    private void changed(String type){metrics.counter("brain.knowledge.external-mapping.changes","type",type).increment();}
    private void read(String type){metrics.counter("brain.knowledge.external-mapping.reads","type",type).increment();}
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
