package io.knowledgebrain.media;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.security.SecureRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
@RestController
@ConditionalOnProperty(name="brain.media.api.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/media")
class MediaController {
 record Created(MediaStore.Info media,ObjectStorage.UploadForm upload){}
 private final MediaStore store;private final ObjectStorage storage;private final SpaceAuthorizationClient space;private final MeterRegistry metrics;private final String origin;private final SecureRandom random=new SecureRandom();
 MediaController(MediaStore store,ObjectStorage storage,SpaceAuthorizationClient space,MeterRegistry metrics,@Value("${brain.media.browser-origin}") String origin){this.store=store;this.storage=storage;this.space=space;this.metrics=metrics;this.origin=origin;}
 @PostMapping ResponseEntity<Created> create(@PathVariable UUID spaceId,HttpServletRequest request) throws java.io.IOException{String parent=parent(request);UUID actor=authorize(spaceId,request,true,parent);var input=MediaInput.parse(body(request));var media=store.create(spaceId,actor,input,parent.substring(3,35));var upload=storage.upload(media);metrics.counter("brain.media.uploads","state","requested").increment();return ResponseEntity.status(201).header("Location","/api/spaces/"+spaceId+"/media/"+media.mediaId()).cacheControl(CacheControl.noStore()).body(new Created(media,upload));}
 @GetMapping("/{mediaId}") ResponseEntity<MediaStore.Info> get(@PathVariable UUID spaceId,@PathVariable UUID mediaId,HttpServletRequest request){authorize(spaceId,request,false,parent(request));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.get(spaceId,mediaId));}
 @GetMapping ResponseEntity<MediaStore.Page> list(@PathVariable UUID spaceId,@RequestParam(required=false) UUID after,@RequestParam(defaultValue="20") int limit,HttpServletRequest request){authorize(spaceId,request,false,parent(request));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(spaceId,after,limit));}
 @PostMapping("/{mediaId}/upload-form") ResponseEntity<ObjectStorage.UploadForm> upload(@PathVariable UUID spaceId,@PathVariable UUID mediaId,HttpServletRequest request) throws java.io.IOException{authorize(spaceId,request,true,parent(request));empty(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(storage.upload(store.get(spaceId,mediaId)));}
 @PostMapping("/{mediaId}/complete") ResponseEntity<MediaStore.Info> complete(@PathVariable UUID spaceId,@PathVariable UUID mediaId,HttpServletRequest request) throws java.io.IOException{String parent=parent(request);UUID actor=authorize(spaceId,request,true,parent);empty(request);var media=store.get(spaceId,mediaId);if("AVAILABLE".equals(media.state()))return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(media);String version=storage.verifyAndSeal(media);var result=store.complete(spaceId,mediaId,actor,version,parent.substring(3,35));metrics.counter("brain.media.uploads","state","completed").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);}
 @PostMapping("/{mediaId}/download") ResponseEntity<ObjectStorage.Download> download(@PathVariable UUID spaceId,@PathVariable UUID mediaId,HttpServletRequest request) throws java.io.IOException{String parent=parent(request);authorize(spaceId,request,false,parent);sameOrigin(request);empty(request);metrics.counter("brain.media.downloads").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(storage.download(store.stored(spaceId,mediaId)));}
 private void empty(HttpServletRequest request) throws java.io.IOException{byte[] bytes=body(request);if(bytes.length>2048)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Request exceeds limit");try{var node=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(bytes);if(!node.isObject() || node.size()!=0)throw new IllegalArgumentException();}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Empty JSON object required");}}
 private byte[] body(HttpServletRequest request) throws java.io.IOException{if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");return request.getInputStream().readNBytes(2049);}
 private void sameOrigin(HttpServletRequest request){if(Collections.list(request.getHeaders("Origin")).size()!=1 || !origin.equals(request.getHeader("Origin")) || request.getHeader("Sec-Fetch-Site")!=null && !"same-origin".equals(request.getHeader("Sec-Fetch-Site")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");}
    UUID authorize(UUID spaceId,HttpServletRequest request,boolean write,String parent) {
        MediaStore.id(spaceId);if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");
        if(write && (Collections.list(request.getHeaders("Origin")).size()!=1 || !origin.equals(request.getHeader("Origin")) || (request.getHeader("Sec-Fetch-Site")!=null && !"same-origin".equals(request.getHeader("Sec-Fetch-Site")))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
        String token=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if("__Host-brain_guest".equals(cookie.getName())) { if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential");token=cookie.getValue(); }
        return space.authorize(spaceId,token,write,parent);
    }
    String parent(HttpServletRequest request) {
        String value=request.getHeader("traceparent");if(value!=null && value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") && !value.substring(3,35).equals("0".repeat(32)) && !value.substring(36,52).equals("0".repeat(16)))return value;
        byte[] trace=new byte[16],span=new byte[8];random.nextBytes(trace);random.nextBytes(span);return "00-"+HexFormat.of().formatHex(trace)+"-"+HexFormat.of().formatHex(span)+"-01";
    }
}
