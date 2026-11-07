package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.MeterRegistry;
@RestController @ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/courses/{courseId}/my-feedback")
final class CourseMyFeedbackController {
 private final CourseMyFeedbackStore store;private final StudyController gate;private final MeterRegistry metrics;
 CourseMyFeedbackController(CourseMyFeedbackStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 @GetMapping ResponseEntity<CourseMyFeedbackStore.Page> list(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("after","limit","filter"));gate.empty(r);var group=GroupInput.id(groupId);var course=GroupInput.id(courseId);var after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)gate.number(r.getParameter("limit"),20,100);String filter=Optional.ofNullable(r.getParameter("filter")).orElse("ALL");if(!CourseMyFeedbackStore.FILTERS.contains(filter))throw GroupInput.bad();var a=gate.participant(group,r,false,gate.trace(r));var p=store.list(group,a.space(),course,a.actor(),after,limit,filter);metrics.counter("brain.collaboration.submission.reads","type","course-mine-feedback").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(p);
 }
}
