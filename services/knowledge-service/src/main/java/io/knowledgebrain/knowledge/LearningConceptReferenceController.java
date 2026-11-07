package io.knowledgebrain.knowledge;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.knowledge.api.enabled",havingValue="true")
final class LearningConceptReferenceController {
 record Reference(UUID spaceId,UUID conceptId,UUID conceptSpaceId,UUID principalId,long version,String title,String visibility){}
 private final ConceptStore concepts;private final ConceptController browser;private final SpaceAuthorizationClient authority;private final MeterRegistry metrics;private final boolean publicEnabled;
 LearningConceptReferenceController(ConceptStore concepts,ConceptController browser,SpaceAuthorizationClient authority,MeterRegistry metrics,@org.springframework.beans.factory.annotation.Value("${brain.knowledge.public.enabled:false}")boolean publicEnabled){this.concepts=concepts;this.browser=browser;this.authority=authority;this.metrics=metrics;this.publicEnabled=publicEnabled;}
 @GetMapping("/api/spaces/{spaceId}/concept-references/{conceptId}/learning-reference")
 ResponseEntity<Reference> read(@PathVariable UUID spaceId,@PathVariable UUID conceptId,HttpServletRequest request){
  ConceptStore.id(conceptId);if(!request.getParameterMap().isEmpty()||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Empty reference read required");String trace=browser.parent(request);UUID actor=browser.authorize(spaceId,request,false,trace);var ref=concepts.learningReference(spaceId,conceptId);
  if(ref.visibility().equals("PUBLIC")){if(!publicEnabled)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Public Concept reference unavailable");String token=null;if(request.getCookies()!=null)for(var c:request.getCookies())if(c.getName().equals("__Host-brain_guest")){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Duplicate browser credential");token=c.getValue();}UUID publicActor=authority.authorize(ref.spaceId(),token,"PUBLIC_CONCEPT_READ","PUBLIC",trace);if(!actor.equals(publicActor))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authority principal changed");}
  metrics.counter("brain.knowledge.learning.references","visibility",ref.visibility()).increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Reference(spaceId,conceptId,ref.spaceId(),actor,ref.version(),ref.title(),ref.visibility()));
 }
}
