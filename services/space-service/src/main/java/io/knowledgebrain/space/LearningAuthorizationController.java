package io.knowledgebrain.space;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@ConditionalOnProperty(name="brain.space.learning-authorization.enabled",havingValue="true")
final class LearningAuthorizationController {
 record Check(UUID spaceId,String token,String action){@Override public String toString(){return "Check[REDACTED]";}}
 record Decision(UUID spaceId,UUID principalId,String action,String visibility){}
 @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
 private final PersonalSpaceStore spaces;private final IdentityPrincipalClient identity;private final byte[] key;
 LearningAuthorizationController(PersonalSpaceStore spaces,IdentityPrincipalClient identity,@Value("${brain.space.learning-authorization.key}")String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Dedicated Learning credential required");this.spaces=spaces;this.identity=identity;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 @PostMapping("/internal/space/learning-authorization") ResponseEntity<Decision> authorize(@RequestBody Check check,HttpServletRequest r){var auth=Collections.list(r.getHeaders("Authorization"));if(!r.isSecure()||auth.size()!=1||!MessageDigest.isEqual(key,auth.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service credential required");if(r.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service credential only");if(check==null||check.spaceId()==null||check.spaceId().version()!=7||check.spaceId().variant()!=2||check.action()==null||!Set.of("ROADMAP_READ","ROADMAP_WRITE","GITHUB_EVIDENCE_READ","GITHUB_EVIDENCE_WRITE","PUBLIC_ROADMAP_READ","ROADMAP_PROPOSE","ROADMAP_REVIEW","ROADMAP_MERGE","ROADMAP_RATING_READ","ROADMAP_RATE","GROUP_ROADMAP_READ","GROUP_ROADMAP_WRITE").contains(check.action()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Learning action");var principal=identity.resolve(check.token(),r.getHeader("traceparent"));if(Set.of("GITHUB_EVIDENCE_READ","GITHUB_EVIDENCE_WRITE").contains(check.action())){if(!principal.kind().equals("REGISTERED"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered account required");var target=spaces.visible(check.spaceId(),principal.principalId());if(target==null||!target.kind().equals("PERSONAL")||!target.visibility().equals("PRIVATE"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Personal Space unavailable");if(!target.role().equals("OWNER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Personal owner required");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PRIVATE"));}if(Set.of("GROUP_ROADMAP_READ","GROUP_ROADMAP_WRITE").contains(check.action())){
  if(!principal.kind().equals("REGISTERED"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered account required");
  var group=spaces.visible(check.spaceId(),principal.principalId());
  if(group==null||!group.kind().equals("GROUP")||!group.visibility().equals("PRIVATE")||!Set.of("OWNER","MEMBER","VIEWER").contains(group.role()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Group unavailable");
  if(check.action().equals("GROUP_ROADMAP_WRITE")&&!group.role().equals("OWNER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Group owner required");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PRIVATE"));
 }if(!Set.of("ROADMAP_READ","ROADMAP_WRITE").contains(check.action())){String publicAction=switch(check.action()){case "PUBLIC_ROADMAP_READ"->"PUBLIC_CONCEPT_READ";case "ROADMAP_PROPOSE","ROADMAP_RATING_READ","ROADMAP_RATE"->"CONTRIBUTION_PROPOSE";case "ROADMAP_REVIEW"->"CONTRIBUTION_REVIEW";case "ROADMAP_MERGE"->"CONTRIBUTION_MERGE";default->throw new IllegalStateException();};new PublicKnowledgeSpaces(dataSource).authorize(check.spaceId(),principal,publicAction);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PUBLIC"));}var space=spaces.visible(check.spaceId(),principal.principalId());if(space==null||!space.kind().equals("PERSONAL")||!space.visibility().equals("PRIVATE"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable");if(check.action().equals("ROADMAP_WRITE")&&!Set.of("OWNER","ADMIN","EDITOR").contains(space.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Roadmap write unavailable");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PRIVATE"));}
}
