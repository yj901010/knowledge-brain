package io.knowledgebrain.media;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@ConditionalOnProperty(name="brain.media.api.enabled",havingValue="true")
class InternalAttachmentsController {
 record Check(UUID spaceId,List<UUID> mediaIds){}
 private final MediaStore store;private final byte[] key;
 InternalAttachmentsController(MediaStore store,@Value("${brain.media.content-authorization-key}") String key){if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Service key required");this.store=store;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);}
 @PostMapping("/internal/media/attachment-check") ResponseEntity<List<MediaStore.Info>> check(@RequestBody Check check,HttpServletRequest request){var headers=Collections.list(request.getHeaders("Authorization"));if(!request.isSecure() || headers.size()!=1 || !MessageDigest.isEqual(key,headers.getFirst().getBytes(StandardCharsets.US_ASCII)))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Service authentication required");MediaStore.id(check.spaceId());if(check.mediaIds()==null || check.mediaIds().size()>20 || new HashSet<>(check.mediaIds()).size()!=check.mediaIds().size())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Media IDs");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(check.mediaIds().stream().map(id->store.available(check.spaceId(),id)).toList());}
}
