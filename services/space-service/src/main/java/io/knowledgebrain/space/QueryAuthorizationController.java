package io.knowledgebrain.space;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@ConditionalOnProperty(name="brain.space.query-authorization.enabled",havingValue="true")
class QueryAuthorizationController {
 record Check(UUID spaceId,String token,String action) { @Override public String toString(){return "Check[REDACTED]";} }
 record Decision(UUID spaceId,UUID principalId,String action,String visibility) {}
 @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
 private final PersonalSpaceStore spaces;private final IdentityPrincipalClient identity;private final byte[] key;
 QueryAuthorizationController(PersonalSpaceStore spaces,IdentityPrincipalClient identity,@Value("${brain.space.query-authorization.key}") String key) {
  if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Scoped query service key required");this.spaces=spaces;this.identity=identity;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);
 }
 @PostMapping("/internal/space/query-authorization") ResponseEntity<Decision> authorize(@RequestBody Check check,HttpServletRequest request) {
  var headers=Collections.list(request.getHeaders("Authorization"));
  if(!request.isSecure() || headers.size()!=1 || !MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");
  if(request.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service credential only");
  if(check.spaceId()==null || check.spaceId().version()!=7 || check.spaceId().variant()!=2 || !Set.of("NOTE_SEARCH","NOTE_GRAPH","BRAIN_SEARCH","BRAIN_GRAPH","PUBLIC_SEARCH","PUBLIC_GRAPH").contains(Optional.ofNullable(check.action()).orElse("")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid query authorization request");
  var principal=identity.resolve(check.token(),request.getHeader("traceparent"));if(Set.of("PUBLIC_SEARCH","PUBLIC_GRAPH").contains(check.action())){new PublicKnowledgeSpaces(dataSource).authorize(check.spaceId(),principal,"PUBLIC_CONCEPT_READ");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PUBLIC"));}var space=spaces.visible(check.spaceId(),principal.principalId());
  if(space==null || !"PERSONAL".equals(space.kind()) || !"PRIVATE".equals(space.visibility()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(space.spaceId(),principal.principalId(),check.action(),"PRIVATE"));
 }
}
