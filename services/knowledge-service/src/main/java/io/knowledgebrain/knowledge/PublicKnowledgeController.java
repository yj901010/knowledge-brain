package io.knowledgebrain.knowledge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name="brain.knowledge.public.enabled",havingValue="true")
@RequestMapping("/api/public/spaces/{spaceId}")
class PublicKnowledgeController {
    private final ContributionStore contributions;private final ConceptStore concepts;private final SpaceAuthorizationClient space;private final ConceptController browser;private final MeterRegistry metrics;
    private final String origin;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    PublicKnowledgeController(ContributionStore contributions,ConceptStore concepts,SpaceAuthorizationClient space,ConceptController browser,MeterRegistry metrics,@Value("${brain.knowledge.browser-origin}") String origin){this.contributions=contributions;this.concepts=concepts;this.space=space;this.browser=browser;this.metrics=metrics;this.origin=origin;}
    @GetMapping("/concepts") ResponseEntity<?> concepts(@PathVariable UUID spaceId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ",browser.parent(request));page(after,limit);return ok(concepts.list(spaceId,after,limit));}
    @GetMapping("/concepts/{conceptId}") ResponseEntity<?> concept(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request){ConceptStore.id(conceptId);authorize(spaceId,request,"PUBLIC_CONCEPT_READ",browser.parent(request));var value=concepts.get(spaceId,conceptId);if(!"PUBLIC".equals(value.visibility()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Public Concept unavailable");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\""+value.version()+"\"").body(value);}
    @GetMapping("/concepts/{conceptId}/revisions") ResponseEntity<?> revisions(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="5") int limit,HttpServletRequest request){ConceptStore.id(conceptId);authorize(spaceId,request,"PUBLIC_CONCEPT_READ",browser.parent(request));if(before<1||limit<1||limit>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid history cursor");var value=concepts.get(spaceId,conceptId);if(!"PUBLIC".equals(value.visibility()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Public Concept unavailable");return ok(concepts.history(spaceId,conceptId,before,limit));}
    @GetMapping("/contributions") ResponseEntity<?> list(@PathVariable UUID spaceId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ",browser.parent(request));page(after,limit);return ok(contributions.list(spaceId,after,limit));}
    @GetMapping("/contributions/{contributionId}") ResponseEntity<?> get(@PathVariable UUID spaceId,@PathVariable UUID contributionId,HttpServletRequest request){ConceptStore.id(contributionId);authorize(spaceId,request,"PUBLIC_CONCEPT_READ",browser.parent(request));return proposal(contributions.get(spaceId,contributionId),200);}
    @GetMapping("/contributions/{contributionId}/reviews") ResponseEntity<?> reviews(@PathVariable UUID spaceId,@PathVariable UUID contributionId,HttpServletRequest request){ConceptStore.id(contributionId);authorize(spaceId,request,"PUBLIC_CONCEPT_READ",browser.parent(request));return ok(contributions.reviews(spaceId,contributionId));}
    @PostMapping("/contributions") ResponseEntity<?> submit(@PathVariable UUID spaceId,HttpServletRequest request)throws java.io.IOException {
        String trace=browser.parent(request);UUID actor=authorize(spaceId,request,"CONTRIBUTION_PROPOSE",trace);var body=body(request,Set.of("conceptId","baseVersion","title","description","rationale"));
        UUID target=null;try{if(!body.path("conceptId").isNull())target=UUID.fromString(string(body,"conceptId"));}catch(IllegalArgumentException error){throw bad();}
        var version=body.path("baseVersion");if(!version.isIntegralNumber()||!version.canConvertToLong())throw bad();
        var value=contributions.submit(spaceId,actor,target,version.asLong(),string(body,"title"),string(body,"description"),string(body,"rationale"),trace.substring(3,35));changed("submitted",trace,value);return proposal(value,201);
    }
    @PostMapping("/contributions/{contributionId}/review") ResponseEntity<?> review(@PathVariable UUID spaceId,@PathVariable UUID contributionId,HttpServletRequest request)throws java.io.IOException {
        ConceptStore.id(contributionId);String trace=browser.parent(request);UUID actor=authorize(spaceId,request,"CONTRIBUTION_REVIEW",trace);long version=version(request);var body=body(request,Set.of("decision","reason"));var value=contributions.decide(spaceId,contributionId,actor,version,string(body,"decision"),string(body,"reason"),trace.substring(3,35));changed("reviewed",trace,value);return proposal(value,200);
    }
    @PostMapping("/contributions/{contributionId}/merge") ResponseEntity<?> merge(@PathVariable UUID spaceId,@PathVariable UUID contributionId,HttpServletRequest request)throws java.io.IOException {
        ConceptStore.id(contributionId);String trace=browser.parent(request);UUID actor=authorize(spaceId,request,"CONTRIBUTION_MERGE",trace);long version=version(request);body(request,Set.of());var value=contributions.merge(spaceId,contributionId,actor,version,trace.substring(3,35));changed("merged",trace,value);return proposal(value,200);
    }
    private UUID authorize(UUID spaceId,HttpServletRequest request,String action,String trace){
        ConceptStore.id(spaceId);if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        if(!"PUBLIC_CONCEPT_READ".equals(action)&&(Collections.list(request.getHeaders("Origin")).size()!=1||!origin.equals(request.getHeader("Origin"))||request.getHeader("Sec-Fetch-Site")!=null&&!"same-origin".equals(request.getHeader("Sec-Fetch-Site"))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
        String token=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if("__Host-brain_guest".equals(cookie.getName())){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Duplicate browser credential");token=cookie.getValue();}
        return space.authorize(spaceId,token,action,"PUBLIC",trace);
    }
    private tools.jackson.databind.JsonNode body(HttpServletRequest request,Set<String> fields)throws java.io.IOException {
        if(!"application/json".equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
        var bytes=request.getInputStream().readNBytes(65537);if(bytes.length>65536)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
        try{var value=json.readTree(bytes);if(value==null||!value.isObject()||value.size()!=fields.size()||fields.stream().anyMatch(key->!value.has(key)))throw bad();return value;}catch(RuntimeException error){throw bad();}
    }
    private static String string(tools.jackson.databind.JsonNode body,String field){if(!body.path(field).isString())throw bad();return body.path(field).asString();}
    private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid contribution body");}
    private static void page(UUID after,int limit){if(after!=null)ConceptStore.id(after);if(limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid page limit");}
    private static long version(HttpServletRequest request){var values=Collections.list(request.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");if(values.size()!=1||!values.getFirst().matches("\"p[1-9][0-9]{0,18}\""))throw bad();try{return Long.parseLong(values.getFirst().substring(2,values.getFirst().length()-1));}catch(NumberFormatException error){throw bad();}}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static ResponseEntity<?> proposal(ContributionStore.Proposal value,int status){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).eTag("\"p"+value.version()+"\"").body(value);}
    private void changed(String type,String trace,ContributionStore.Proposal value){metrics.counter("brain.knowledge.contribution.changes","type",type).increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("contribution_changed state={} contribution_id={} trace_id={}",value.state(),value.contributionId(),trace.substring(3,35));}
}
