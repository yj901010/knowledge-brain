package io.knowledgebrain.content;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;
@RestController
@ConditionalOnProperty(name="brain.content.attachments.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/notes/{noteId}/attachments")
class NoteAttachmentsController {
 private final NoteStore notes;private final NoteController guard;private final MediaAvailabilityClient media;private final MeterRegistry metrics;
 NoteAttachmentsController(NoteStore notes,NoteController guard,MediaAvailabilityClient media,MeterRegistry metrics){this.notes=notes;this.guard=guard;this.media=media;this.metrics=metrics;}
 @GetMapping ResponseEntity<NoteAttachmentStore.Snapshot> get(@PathVariable UUID spaceId,@PathVariable UUID noteId,HttpServletRequest request){NoteStore.id(noteId);guard.authorize(spaceId,request,false,guard.parent(request));return response(notes.attachments(spaceId,noteId));}
 @PutMapping ResponseEntity<NoteAttachmentStore.Snapshot> replace(@PathVariable UUID spaceId,@PathVariable UUID noteId,HttpServletRequest request) throws java.io.IOException {
  NoteStore.id(noteId);String parent=guard.parent(request);UUID actor=guard.authorize(spaceId,request,true,parent);var headers=Collections.list(request.getHeaders("If-Match"));if(headers.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");if(headers.size()!=1 || !headers.getFirst().matches("\"a(0|[1-9][0-9]{0,18})\""))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Attachment ETag required");long version;try{version=Long.parseLong(headers.getFirst().substring(2,headers.getFirst().length()-1));}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid attachment version");}
  if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(request.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");byte[] body=request.getInputStream().readNBytes(2049);if(body.length>2048)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Attachment request exceeds 2 KiB");var ids=new ArrayList<UUID>();try{var node=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readTree(body);if(!node.isObject() || node.size()!=1 || !node.path("mediaIds").isArray() || node.path("mediaIds").size()>20)throw new IllegalArgumentException();for(var item:node.path("mediaIds")){if(!item.isString())throw new IllegalArgumentException();UUID id=UUID.fromString(item.asString());NoteStore.id(id);ids.add(id);}if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException();}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected unique UUIDv7 Media IDs");}
  notes.get(spaceId,noteId);var files=media.check(spaceId,ids,parent);var result=notes.attachments(spaceId,noteId,actor,version,files,parent.substring(3,35));metrics.counter("brain.content.attachment.changes").increment();return response(result);
 }
 @GetMapping("/revisions") ResponseEntity<List<NoteAttachmentStore.Revision>> history(@PathVariable UUID spaceId,@PathVariable UUID noteId,@RequestParam(defaultValue="9223372036854775807") long before,@RequestParam(defaultValue="5") int limit,HttpServletRequest request){NoteStore.id(noteId);guard.authorize(spaceId,request,false,guard.parent(request));if(before<1 || limit<1 || limit>10)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid attachment history cursor");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notes.attachmentHistory(spaceId,noteId,before,limit));}
 private ResponseEntity<NoteAttachmentStore.Snapshot> response(NoteAttachmentStore.Snapshot value){return ResponseEntity.ok().eTag("\"a"+value.attachmentVersion()+"\"").cacheControl(CacheControl.noStore()).body(value);}
}
