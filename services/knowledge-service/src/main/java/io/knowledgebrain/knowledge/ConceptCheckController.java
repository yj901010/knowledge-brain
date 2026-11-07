package io.knowledgebrain.knowledge;
import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import jakarta.servlet.http.HttpServletRequest;import org.springframework.beans.factory.annotation.Value;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.web.bind.annotation.*;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;
@RestController @ConditionalOnProperty(name="brain.knowledge.api.enabled",havingValue="true")
class ConceptCheckController {
 record Check(UUID spaceId,String token,List<UUID> conceptIds){@Override public String toString(){return "Check[REDACTED]";}}
 private final ConceptStore concepts;private final SpaceAuthorizationClient authority;private final byte[] key;
 ConceptCheckController(ConceptStore concepts,SpaceAuthorizationClient authority,@Value("${brain.knowledge.content-key}") String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException();this.concepts=concepts;this.authority=authority;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 @PostMapping("/internal/knowledge/concept-check") ResponseEntity<List<UUID>> check(@RequestBody Check check,HttpServletRequest request){var headers=Collections.list(request.getHeaders("Authorization"));if(!request.isSecure()||headers.size()!=1||!MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");ConceptStore.id(check.spaceId());if(check.conceptIds()==null||check.conceptIds().size()>20||new HashSet<>(check.conceptIds()).size()!=check.conceptIds().size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Concept IDs");check.conceptIds().forEach(ConceptStore::id);String parent=request.getHeader("traceparent");UUID actor=authority.authorize(check.spaceId(),check.token(),false,parent);
  var publicSpaces=new HashSet<UUID>();
  for(var id:check.conceptIds()) {
   var reference=concepts.reference(check.spaceId(),id);
   if("PUBLIC".equals(reference.visibility()))publicSpaces.add(reference.spaceId());
  }
  for(var publicSpace:publicSpaces) {
   UUID publicActor=authority.authorize(publicSpace,check.token(),"PUBLIC_CONCEPT_READ","PUBLIC",parent);
   if(!actor.equals(publicActor))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authority principal changed");
  }
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(check.conceptIds());}
}
