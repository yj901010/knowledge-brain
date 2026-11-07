package io.knowledgebrain.content;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
/** Narrow owner read: no Markdown, media credentials or other domain data. */
@RestController
@ConditionalOnProperty(name="brain.content.api.enabled",havingValue="true")
final class NoteReferenceController {
 record Reference(UUID spaceId,UUID noteId,UUID principalId,long version,String title,String visibility){}
 private final NoteStore notes;private final NoteController authority;
 NoteReferenceController(NoteStore notes,NoteController authority){this.notes=notes;this.authority=authority;}
 @GetMapping("/api/spaces/{spaceId}/notes/{noteId}/reference")
 ResponseEntity<Reference> reference(@PathVariable UUID spaceId,@PathVariable UUID noteId,HttpServletRequest request){NoteStore.id(noteId);UUID actor=authority.authorize(spaceId,request,false,authority.parent(request));var note=notes.get(spaceId,noteId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Reference(spaceId,noteId,actor,note.version(),note.title(),note.visibility()));}
}
