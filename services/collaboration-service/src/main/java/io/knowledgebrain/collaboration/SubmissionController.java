package io.knowledgebrain.collaboration;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.MeterRegistry;
@RestController
@ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/studies/{studyId}/assignments/{assignmentId}/submissions")
final class SubmissionController {
 private final SubmissionStore store;private final StudyController gate;private final MeterRegistry metrics;
 SubmissionController(SubmissionStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 static String tag(long version){return "\"su"+version+"\"";}
 static long expected(HttpServletRequest r){var v=Collections.list(r.getHeaders("If-Match"));if(v.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");if(v.size()!=1||!v.getFirst().matches("\"su(0|[1-9][0-9]{0,18})\""))throw GroupInput.bad();try{return Long.parseLong(v.getFirst().substring(3,v.getFirst().length()-1));}catch(NumberFormatException e){throw GroupInput.bad();}}
 private boolean writer(StudyController.Access a){return Set.of("OWNER","MEMBER").contains(a.role());}
 private ResponseEntity<?> value(SubmissionStore.Submission v){return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);}
 @GetMapping("/mine") ResponseEntity<?> mine(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId);var a=gate.participant(group,r,false,gate.trace(r));var v=store.mine(group,a.space(),study,assignment,a.actor(),writer(a));metrics.counter("brain.collaboration.submission.reads","type","mine").increment();return ResponseEntity.ok().eTag(tag(v.submission()==null?0:v.submission().version())).cacheControl(CacheControl.noStore()).body(v);
 }
 @PostMapping ResponseEntity<?> submit(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId);String trace=gate.trace(r);var a=gate.participant(group,r,true,trace);if(!writer(a))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Submission writer required");
  long expected=expected(r);var keys=Collections.list(r.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw GroupInput.bad();var input=SubmissionInput.parse(gate.jsonBody(r));var result=store.submit(group,a.space(),study,assignment,a.actor(),expected,input.assignmentVersion(),keys.getFirst(),input.content(),trace.substring(3,35));var v=result.submission();metrics.counter("brain.collaboration.submission.changes","type",result.applied()?"submitted":"replayed").increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("assignment_submission type={} submission_id={} trace_id={}",result.applied()?"submitted":"replayed",v.submissionId(),trace.substring(3,35));
  return ResponseEntity.status(result.applied()?201:200).header("Location","/api/groups/"+group+"/studies/"+study+"/assignments/"+assignment+"/submissions/"+v.submissionId()).eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);
 }
 @GetMapping ResponseEntity<?> list(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("after","limit"));gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId),after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)gate.number(r.getParameter("limit"),20,100);var a=gate.participant(group,r,false,gate.trace(r));if(!a.role().equals("OWNER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Group owner required");metrics.counter("brain.collaboration.submission.reads","type","catalog").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(group,a.space(),study,assignment,after,limit));
 }
 @GetMapping("/{submissionId}") ResponseEntity<?> get(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId),id=GroupInput.id(submissionId);var a=gate.participant(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.submission.reads","type","current").increment();return value(store.get(group,a.space(),study,assignment,id,a.actor(),a.role().equals("OWNER")));
 }
 @GetMapping("/{submissionId}/revisions/{version}/instructions") ResponseEntity<?> instructions(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,@PathVariable String version,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId),id=GroupInput.id(submissionId);long attempt=gate.number(version,0,Long.MAX_VALUE);if(attempt<1)throw GroupInput.bad();var a=gate.participant(group,r,false,gate.trace(r));
  var v=store.instructions(group,a.space(),study,assignment,id,a.actor(),a.role().equals("OWNER"),attempt);metrics.counter("brain.collaboration.submission.reads","type","instructions").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(v);
 }
 @GetMapping("/{submissionId}/revisions") ResponseEntity<?> history(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,@PathVariable String submissionId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("before","limit"));gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),assignment=GroupInput.id(assignmentId),id=GroupInput.id(submissionId);long before=gate.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)gate.number(r.getParameter("limit"),5,10);var a=gate.participant(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.submission.reads","type","history").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(group,a.space(),study,assignment,id,a.actor(),a.role().equals("OWNER"),before,limit));
 }
}
