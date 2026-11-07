package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.MeterRegistry;
@RestController @ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/studies/{studyId}/assignments/{assignmentId}/rubric")
final class AssignmentRubricController {
 private final AssignmentRubricStore store;private final StudyController gate;private final MeterRegistry metrics;
 AssignmentRubricController(AssignmentRubricStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 static String tag(long version){return "\"rb"+version+"\"";}
 static long expected(HttpServletRequest r){var values=Collections.list(r.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Rubric If-Match required");if(values.size()!=1||!values.getFirst().matches("\"rb(0|[1-9][0-9]{0,18})\""))throw GroupInput.bad();try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw GroupInput.bad();}}
 @GetMapping ResponseEntity<?> get(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);var a=gate.participant(group,r,false,gate.trace(r));var v=store.get(group,a.space(),study,assignment,a.role().equals("OWNER"));metrics.counter("brain.collaboration.assignment.rubric.reads","type","current").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(tag(v.rubric()==null?0:v.rubric().version())).body(v);
 }
 @PutMapping ResponseEntity<?> change(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long expected=expected(r);var input=AssignmentRubricInput.parse(gate.jsonBody(r));var v=store.change(group,a.space(),study,assignment,a.actor(),expected,input,trace.substring(3,35));metrics.counter("brain.collaboration.assignment.rubric.changes").increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("assignment_rubric rubric_id={} trace_id={}",v.rubric()==null?null:v.rubric().rubricId(),trace.substring(3,35));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(tag(v.rubric()==null?0:v.rubric().version())).body(v);
 }
 @GetMapping("/revisions") ResponseEntity<?> history(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("before","limit"));gate.empty(r);var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);long before=gate.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)gate.number(r.getParameter("limit"),5,10);var a=gate.participant(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.assignment.rubric.reads","type","history").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(group,a.space(),study,assignment,before,limit));
 }
}
