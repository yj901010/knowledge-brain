package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.MeterRegistry;
@RestController @ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/studies/{studyId}/assignments/{assignmentId}/submissions/grading-queue")
final class SubmissionGradingQueueController {
 private final SubmissionGradingQueueStore store;private final StudyController gate;private final MeterRegistry metrics;
 SubmissionGradingQueueController(SubmissionGradingQueueStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 @GetMapping ResponseEntity<SubmissionGradingQueueStore.Page> list(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("after","limit","filter"));gate.empty(r);var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);var after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)gate.number(r.getParameter("limit"),20,100);String filter=Optional.ofNullable(r.getParameter("filter")).orElse("NEEDS_GRADING");if(!SubmissionGradingQueueStore.FILTERS.contains(filter))throw GroupInput.bad();var a=gate.participant(group,r,false,gate.trace(r));if(!a.role().equals("OWNER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Group owner required");var p=store.list(group,a.space(),study,assignment,a.actor(),after,limit,filter);metrics.counter("brain.collaboration.submission.reads","type","grading-queue").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(p);
 }
}
