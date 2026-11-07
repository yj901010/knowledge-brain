package io.knowledgebrain.media;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
/** Owner-authorized metadata only. Never exposes object keys, versions or signed credentials. */
@RestController
@ConditionalOnProperty(name="brain.media.api.enabled",havingValue="true")
final class LearningMediaReferenceController {
 record Reference(UUID spaceId,UUID mediaId,UUID principalId,long version,String filename,String mimeType,long byteSize,String sha256,String state,String visibility){}
 private final MediaStore store;private final MediaController authority;private final MeterRegistry metrics;
 LearningMediaReferenceController(MediaStore store,MediaController authority,MeterRegistry metrics){this.store=store;this.authority=authority;this.metrics=metrics;}
 @GetMapping("/api/spaces/{spaceId}/media/{mediaId}/learning-reference") ResponseEntity<Reference> get(@PathVariable UUID spaceId,@PathVariable UUID mediaId,HttpServletRequest request){
  MediaStore.id(mediaId);if(!request.getParameterMap().isEmpty()||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Body and query are not supported");
  UUID actor=authority.authorize(spaceId,request,false,authority.parent(request));var info=store.available(spaceId,mediaId);metrics.counter("brain.media.learning.references").increment();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Reference(spaceId,mediaId,actor,info.version(),info.filename(),info.mimeType(),info.byteSize(),info.sha256(),info.state(),"PRIVATE"));
 }
}
