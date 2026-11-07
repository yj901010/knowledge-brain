package io.knowledgebrain.space;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name="brain.space.api.enabled",havingValue="true")
class SpaceController {
    private final PersonalSpaceStore spaces;
    private final IdentityPrincipalClient identity;
    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;
    SpaceController(PersonalSpaceStore spaces,IdentityPrincipalClient identity) { this.spaces=spaces;this.identity=identity; }
    @GetMapping("/api/spaces/public")
    ResponseEntity<?> publicSpaces(HttpServletRequest request){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("items",new PublicKnowledgeSpaces(dataSource).list(principal(request))));}
    @GetMapping("/api/spaces/personal")
    ResponseEntity<PersonalSpaceStore.SpaceView> personal(HttpServletRequest request) {
        var principal=principal(request);var space=spaces.personal(principal.principalId());
        if(space==null)return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).header("Retry-After","1").build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(space);
    }
    @GetMapping("/api/spaces/personal/all")
    ResponseEntity<?> owned(HttpServletRequest request) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(java.util.Map.of("items",spaces.owned(principal(request).principalId())));}
    @GetMapping("/api/spaces/claims/{guestId}")
    ResponseEntity<PersonalSpaceStore.SpaceView> claimed(@PathVariable UUID guestId,HttpServletRequest request) {
        var account=principal(request);if(!"REGISTERED".equals(account.kind()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Registered account required");
        var space=spaces.claimed(guestId,account.principalId());if(space==null)return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).header("Retry-After","1").build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(space);
    }
    @GetMapping("/api/spaces/{spaceId}")
    ResponseEntity<PersonalSpaceStore.SpaceView> get(@PathVariable UUID spaceId,HttpServletRequest request) {
        var principal=principal(request);var space=spaces.visible(spaceId,principal.principalId());
        if(space==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(space);
    }
    private IdentityPrincipalClient.Principal principal(HttpServletRequest request) {
        if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        String token=null;
        if(request.getCookies()!=null)for(var cookie:request.getCookies())if("__Host-brain_guest".equals(cookie.getName())) {
            if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential");token=cookie.getValue();
        }
        return identity.resolve(token);
    }
}
