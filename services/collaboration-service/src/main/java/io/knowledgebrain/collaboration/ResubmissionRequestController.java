package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.MeterRegistry;
@RestController @ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/studies/{studyId}/assignments/{assignmentId}/submissions/{submissionId}/resubmission-request")
final class ResubmissionRequestController {
 private final ResubmissionRequestStore store;private final StudyController gate;private final MeterRegistry metrics;
 ResubmissionRequestController(ResubmissionRequestStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 static String tag(long version){return "\"rr"+version+"\"";}
 static long expected(HttpServletRequest r){var values=Collections.list(r.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Request If-Match required");if(values.size()!=1||!values.getFirst().matches("\"rr(0|[1-9][0-9]{0,18})\""))throw GroupInput.bad();try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw GroupInput.bad();}}
 @GetMapping ResponseEntity<?> get(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);var id=GroupInput.id(submissionId);var a=gate.participant(group,r,false,gate.trace(r));var v=store.get(group,a.space(),study,assignment,id,a.actor(),a.role().equals("OWNER"));metrics.counter("brain.collaboration.resubmission.request.reads","type","current").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(tag(v.request()==null?0:v.request().version())).body(v);
 }
 @PutMapping ResponseEntity<?> change(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);var id=GroupInput.id(submissionId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long expected=expected(r);var input=ResubmissionRequestInput.parse(gate.jsonBody(r));var v=store.change(group,a.space(),study,assignment,id,a.actor(),expected,input,trace.substring(3,35));metrics.counter("brain.collaboration.resubmission.request.changes").increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("resubmission_request request_id={} trace_id={}",v.requestId(),trace.substring(3,35));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(tag(v.version())).body(v);
 }
 @GetMapping("/revisions") ResponseEntity<?> history(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("before","limit"));gate.empty(r);var group=GroupInput.id(groupId);var study=GroupInput.id(studyId);var assignment=GroupInput.id(assignmentId);var id=GroupInput.id(submissionId);long before=gate.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)gate.number(r.getParameter("limit"),5,10);var a=gate.participant(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.resubmission.request.reads","type","history").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(group,a.space(),study,assignment,id,a.actor(),a.role().equals("OWNER"),before,limit));
 }
}
