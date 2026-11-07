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
@ConditionalOnProperty(name="brain.space.notification-authorization.enabled",havingValue="true")
final class NotificationAuthorizationController {
 record Check(UUID spaceId,String token,String action){@Override public String toString(){return "Check[REDACTED]";}}
 record Decision(UUID spaceId,UUID principalId,String action,String visibility){}
 private final PersonalSpaceStore spaces;private final IdentityPrincipalClient identity;private final byte[] key;
 NotificationAuthorizationController(PersonalSpaceStore spaces,IdentityPrincipalClient identity,@Value("${brain.space.notification-authorization.key}")String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Dedicated Notification credential required");this.spaces=spaces;this.identity=identity;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 @PostMapping("/internal/space/notification-authorization") ResponseEntity<Decision> authorize(@RequestBody Check check,HttpServletRequest r){var auth=Collections.list(r.getHeaders("Authorization"));if(!r.isSecure()||auth.size()!=1||!MessageDigest.isEqual(key,auth.getFirst().getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service credential required");if(r.getHeader("Cookie")!=null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Service credential only");if(check==null||check.spaceId()==null||check.spaceId().version()!=7||check.spaceId().variant()!=2||check.action()==null||!Set.of("NOTIFICATION_READ","NOTIFICATION_WRITE").contains(check.action()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Notification action");var principal=identity.resolve(check.token(),r.getHeader("traceparent"));var space=spaces.visible(check.spaceId(),principal.principalId());if(space==null||!space.kind().equals("PERSONAL")||!space.visibility().equals("PRIVATE"))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable");if(Set.of("NOTIFICATION_WRITE").contains(check.action())&&!Set.of("OWNER","ADMIN","EDITOR").contains(space.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Goal write unavailable");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Decision(check.spaceId(),principal.principalId(),check.action(),"PRIVATE"));}
}
