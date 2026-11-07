package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import io.micrometer.core.instrument.MeterRegistry;
@RestController
@ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
final class CourseLearningContextController {
 private final CourseEnrollmentStore store;private final StudyController gate;private final MeterRegistry metrics;
 CourseLearningContextController(CourseEnrollmentStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 @GetMapping("/api/groups/{groupId}/courses/{courseId}/learning-context") ResponseEntity<?> context(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),course=GroupInput.id(courseId);var a=gate.participant(group,r,false,gate.trace(r));var v=store.learningContext(group,a.space(),course,a.actor(),a.role());metrics.counter("brain.collaboration.course.enrollment.reads","type","learning_context").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);}
}
