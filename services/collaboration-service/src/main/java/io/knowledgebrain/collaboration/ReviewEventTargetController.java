package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import io.micrometer.core.instrument.MeterRegistry;
@RestController @ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
final class ReviewEventTargetController {
 private final ReviewEventTargetStore store;private final StudyController gate;private final MeterRegistry metrics;
 ReviewEventTargetController(ReviewEventTargetStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 @GetMapping("/api/groups/{groupId}/studies/{studyId}/assignments/{assignmentId}/submissions/{submissionId}/review-events/{eventId}")
 ResponseEntity<ReviewEventTargetStore.Target> get(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,@PathVariable String eventId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId),submission=GroupInput.id(submissionId),event=GroupInput.id(eventId);var access=gate.participant(group,r,false,gate.trace(r));var target=store.get(group,access.space(),study,assignment,submission,access.actor(),access.role().equals("OWNER"),event);metrics.counter("brain.collaboration.submission.reads","type","notification-original").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(target);
 }
}
