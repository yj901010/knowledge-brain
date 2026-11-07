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
@ConditionalOnProperty(name="brain.space.content-authorization.enabled",havingValue="true")
class ContentAuthorizationController {
    record Check(UUID spaceId,String token,String action) { @Override public String toString() { return "Check[REDACTED]"; } }
    record Decision(UUID spaceId,UUID principalId,String action,String visibility) {}
    private final PersonalSpaceStore spaces;private final IdentityPrincipalClient identity;private final byte[] key;
    ContentAuthorizationController(PersonalSpaceStore spaces,IdentityPrincipalClient identity,@Value("${brain.space.content-authorization.key}") String key) {
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Service key required");this.spaces=spaces;this.identity=identity;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);
    }
    @PostMapping("/internal/space/content-authorization") ResponseEntity<Decision> authorize(@RequestBody Check check,HttpServletRequest request) {
        var headers=Collections.list(request.getHeaders("Authorization"));
        if(!request.isSecure() || headers.size()!=1 || !MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");
        if(check.spaceId()==null || check.spaceId().version()!=7 || check.spaceId().variant()!=2 || !Set.of("NOTE_READ","NOTE_WRITE","BOOKMARK_READ","BOOKMARK_WRITE","QUESTION_READ","QUESTION_WRITE","DECISION_READ","DECISION_WRITE").contains(Optional.ofNullable(check.action()).orElse("")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization request");
        var principal=identity.resolve(check.token(),request.getHeader("traceparent"));var space=spaces.visible(check.spaceId(),principal.principalId());
        if(space==null || !"PERSONAL".equals(space.kind()) || !"PRIVATE".equals(space.visibility()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable");
        if(Set.of("NOTE_WRITE","BOOKMARK_WRITE","QUESTION_WRITE","DECISION_WRITE").contains(check.action()) && !Set.of("OWNER","ADMIN","EDITOR").contains(space.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Note write unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(space.spaceId(),principal.principalId(),check.action(),space.visibility()));
    }
}
