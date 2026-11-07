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
@ConditionalOnProperty(name="brain.space.knowledge-authorization.enabled",havingValue="true")
class KnowledgeAuthorizationController {
    record Check(UUID spaceId,String token,String action) { @Override public String toString() { return "Check[REDACTED]"; } }
    record Decision(UUID spaceId,UUID principalId,String action,String visibility) {}
    private final PersonalSpaceStore spaces;private final IdentityPrincipalClient identity;private final byte[] key;
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
    KnowledgeAuthorizationController(PersonalSpaceStore spaces,IdentityPrincipalClient identity,@Value("${brain.space.knowledge-authorization.key}") String key) {
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Service key required");this.spaces=spaces;this.identity=identity;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);
    }
    @PostMapping("/internal/space/knowledge-authorization") ResponseEntity<Decision> authorize(@RequestBody Check check,HttpServletRequest request) {
        var headers=Collections.list(request.getHeaders("Authorization"));
        if(!request.isSecure() || headers.size()!=1 || !MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");
        if(check.spaceId()==null || check.spaceId().version()!=7 || check.spaceId().variant()!=2 || !Set.of("CONCEPT_READ","CONCEPT_WRITE","PUBLIC_CONCEPT_READ","CONTRIBUTION_PROPOSE","CONTRIBUTION_REVIEW","CONTRIBUTION_MERGE","CLAIM_EXPERT_VERIFY","MAPPING_OFFICIAL_REVIEW").contains(Optional.ofNullable(check.action()).orElse("")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization request");
        var principal=identity.resolve(check.token(),request.getHeader("traceparent"));
        if(!Set.of("CONCEPT_READ","CONCEPT_WRITE").contains(check.action())) {
            new PublicKnowledgeSpaces(dataSource).authorize(check.spaceId(),principal,check.action());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PUBLIC"));
        }
        var space=spaces.visible(check.spaceId(),principal.principalId());
        if(space==null || !"PERSONAL".equals(space.kind()) || !"PRIVATE".equals(space.visibility()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable");
        if("CONCEPT_WRITE".equals(check.action()) && !Set.of("OWNER","ADMIN","EDITOR").contains(space.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Concept write unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(space.spaceId(),principal.principalId(),check.action(),space.visibility()));
    }
}
