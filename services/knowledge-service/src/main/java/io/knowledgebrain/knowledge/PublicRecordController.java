package io.knowledgebrain.knowledge;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@ConditionalOnProperty(name="brain.knowledge.public.enabled",havingValue="true")
@RequestMapping("/api/public/spaces/{spaceId}")
class PublicRecordController {
    private final PublicRecordStore store;private final SpaceAuthorizationClient space;private final ConceptController browser;private final String origin;private final MeterRegistry metrics;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    PublicRecordController(PublicRecordStore store,SpaceAuthorizationClient space,ConceptController browser,MeterRegistry metrics,@Value("${brain.knowledge.browser-origin}") String origin){this.store=store;this.space=space;this.browser=browser;this.origin=origin;this.metrics=metrics;}
    @GetMapping("/records") ResponseEntity<?> list(@PathVariable UUID spaceId,@RequestParam(required=false) String kind,@RequestParam(defaultValue="ACTIVE") String state,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="10") int limit,@RequestParam(defaultValue="") String q,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.list(spaceId,kind,state,after,limit,q));}
    @GetMapping("/explore") ResponseEntity<?> explore(@PathVariable UUID spaceId,@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.explore(spaceId,q,after,limit));}
    @GetMapping("/concepts/{conceptId}/relations") ResponseEntity<?> relations(@PathVariable UUID spaceId,@PathVariable UUID conceptId,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.relations(spaceId,conceptId,limit));}
    @GetMapping("/records/{recordId}") ResponseEntity<?> get(@PathVariable UUID spaceId,@PathVariable UUID recordId,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");var v=store.get(spaceId,recordId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag("\"kr"+v.version()+"\"").body(v);}
    @GetMapping("/records/{recordId}/revisions") ResponseEntity<?> history(@PathVariable UUID spaceId,@PathVariable UUID recordId,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="5") int limit,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.revisions(spaceId,recordId,before,limit));}
    @GetMapping("/records/{recordId}/qualifiers") ResponseEntity<?> qualifiers(@PathVariable UUID spaceId,@PathVariable UUID recordId,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.qualifiers(spaceId,recordId));}
    @GetMapping("/records/{recordId}/verification") ResponseEntity<?> verification(@PathVariable UUID spaceId,@PathVariable UUID recordId,HttpServletRequest request){authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.verification(spaceId,recordId));}
    @GetMapping("/record-proposals") ResponseEntity<?> queue(@PathVariable UUID spaceId,@RequestParam(required=false) String state,@RequestParam(required=false) String kind,@RequestParam(defaultValue="false") boolean mine,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){UUID actor=authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.queue(spaceId,state,kind,mine?actor:null,after,limit));}
    @GetMapping("/record-proposals/{proposalId}") ResponseEntity<?> proposal(@PathVariable UUID spaceId,@PathVariable UUID proposalId,HttpServletRequest request){ConceptStore.id(proposalId);authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return proposal(store.proposal(spaceId,proposalId),200);}
    @GetMapping("/record-proposals/{proposalId}/reviews") ResponseEntity<?> reviews(@PathVariable UUID spaceId,@PathVariable UUID proposalId,HttpServletRequest request){ConceptStore.id(proposalId);authorize(spaceId,request,"PUBLIC_CONCEPT_READ");return ok(store.reviews(spaceId,proposalId));}
    @PostMapping("/record-proposals") ResponseEntity<?> submit(@PathVariable UUID spaceId,HttpServletRequest request)throws java.io.IOException{
        UUID actor=authorize(spaceId,request,"CONTRIBUTION_PROPOSE");JsonNode b=body(request);if(b.has("qualifierPins"))PublicRecordStore.fields(b,"kind","operation","recordId","baseVersion","title","content","rationale","qualifierPins");else PublicRecordStore.fields(b,"kind","operation","recordId","baseVersion","title","content","rationale");
        UUID id=b.path("recordId").isNull()?null:PublicRecordStore.id(b,"recordId");
        var p=store.submit(spaceId,actor,PublicRecordStore.str(b,"kind"),PublicRecordStore.str(b,"operation"),id,PublicRecordStore.number(b,"baseVersion",true),PublicRecordStore.str(b,"title"),b.path("content"),PublicRecordStore.str(b,"rationale"),browser.parent(request).substring(3,35),b.has("qualifierPins")?b.path("qualifierPins"):json.createObjectNode());changed(p);return proposal(p,201);
    }
    @PostMapping("/record-proposals/{proposalId}/{command:review|merge}") ResponseEntity<?> decide(@PathVariable UUID spaceId,@PathVariable UUID proposalId,@PathVariable String command,HttpServletRequest request)throws java.io.IOException{
        ConceptStore.id(proposalId);UUID actor=authorize(spaceId,request,command.equals("merge")?"CONTRIBUTION_MERGE":"CONTRIBUTION_REVIEW");JsonNode b=body(request);
        PublicRecordStore.fields(b,command.equals("merge")?new String[]{"reason"}:new String[]{"decision","reason"});
        String decision=command.equals("merge")?"MERGED":PublicRecordStore.str(b,"decision");if(!command.equals("merge")&&!Set.of("APPROVED","REJECTED").contains(decision))throw PublicRecordStore.bad("Approve or reject required");
        if(!decision.equals("REJECTED")){String extra=store.authority(store.proposal(spaceId,proposalId));if(extra!=null)authorize(spaceId,request,extra);}
        var p=store.decide(spaceId,proposalId,actor,version(request),decision,PublicRecordStore.str(b,"reason"),browser.parent(request).substring(3,35));changed(p);return proposal(p,200);
    }
    private UUID authorize(UUID id,HttpServletRequest request,String action){
        ConceptStore.id(id);if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        String path=request.getRequestURI();Set<String> keys=path.endsWith("/record-proposals")?Set.of("state","kind","mine","after","limit"):path.endsWith("/records")?Set.of("kind","state","after","limit","q"):path.endsWith("/explore")?Set.of("q","after","limit"):path.endsWith("/revisions")?Set.of("before","limit"):path.endsWith("/relations")?Set.of("limit"):Set.of();
        if(request.getParameterMap().entrySet().stream().anyMatch(e->!request.getMethod().equals("GET")||!keys.contains(e.getKey())||e.getValue().length!=1))throw PublicRecordStore.bad("Invalid public query");
        if(!request.getMethod().equals("GET")&&(Collections.list(request.getHeaders("Origin")).size()!=1||!origin.equals(request.getHeader("Origin"))||request.getHeader("Sec-Fetch-Site")!=null&&!"same-origin".equals(request.getHeader("Sec-Fetch-Site"))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
        String token=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if(cookie.getName().equals("__Host-brain_guest")){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Duplicate credential");token=cookie.getValue();}
        return space.authorize(id,token,action,"PUBLIC",browser.parent(request));
    }
    private JsonNode body(HttpServletRequest request)throws java.io.IOException{
        if(!"application/json".equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].strip()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");
        var bytes=request.getInputStream().readNBytes(32769);if(bytes.length>32768)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request too large");
        try{var b=json.readTree(bytes);if(b==null||!b.isObject())throw PublicRecordStore.bad("Object required");return b;}catch(RuntimeException e){throw PublicRecordStore.bad("Invalid proposal body");}
    }
    private long version(HttpServletRequest request){var values=Collections.list(request.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");if(values.size()!=1||!values.getFirst().matches("\"kp[1-9][0-9]{0,18}\""))throw PublicRecordStore.bad("Strong proposal ETag required");try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw PublicRecordStore.bad("Invalid version");}}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    private static ResponseEntity<?> proposal(PublicRecordStore.Proposal p,int status){return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).eTag("\"kp"+p.version()+"\"").body(p);}
    private void changed(PublicRecordStore.Proposal p){metrics.counter("brain.knowledge.public.changes","kind",p.kind(),"state",p.state()).increment();}
}
