package io.knowledgebrain.collaboration;

import java.util.*;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/studies/{studyId}/assignments")
final class AssignmentController {
 private final AssignmentStore store;
 private final StudyController gate;
 private final MeterRegistry metrics;
 AssignmentController(AssignmentStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 static String tag(long version){return "\"as"+version+"\"";}
 static long expected(HttpServletRequest r){
  var values=Collections.list(r.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");
  if(values.size()!=1||!values.getFirst().matches("\"as[1-9][0-9]{0,18}\""))throw GroupInput.bad();try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw GroupInput.bad();}
 }
 private ResponseEntity<?> value(AssignmentStore.Assignment v){return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);}
 private void changed(String type,AssignmentStore.Assignment v,String trace){metrics.counter("brain.collaboration.assignment.changes","type",type).increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("group_assignment type={} assignment_id={} trace_id={}",type,v.assignmentId(),trace.substring(3,35));}
 @PostMapping ResponseEntity<?> create(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);
  var keys=Collections.list(r.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw GroupInput.bad();var input=GroupInput.settings(gate.jsonBody(r));
  var result=store.create(group,a.space(),study,a.actor(),keys.getFirst(),input.title(),input.description(),trace.substring(3,35));changed(result.created()?"created":"existing",result.assignment(),trace);
  return ResponseEntity.status(result.created()?201:200).header("Location","/api/groups/"+group+"/studies/"+study+"/assignments/"+result.assignment().assignmentId()).eTag(tag(result.assignment().version())).cacheControl(CacheControl.noStore()).body(result.assignment());
 }
 @GetMapping ResponseEntity<?> list(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("after","limit"));gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)gate.number(r.getParameter("limit"),20,100);var a=gate.access(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.assignment.reads","type","catalog").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(group,a.space(),study,after,limit));
 }
 @GetMapping("/{assignmentId}") ResponseEntity<?> get(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),id=GroupInput.id(assignmentId);var a=gate.access(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.assignment.reads","type","current").increment();return value(store.get(group,a.space(),study,id));
 }
 @PutMapping("/{assignmentId}") ResponseEntity<?> metadata(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),id=GroupInput.id(assignmentId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long version=expected(r);var input=GroupInput.settings(gate.jsonBody(r));
  var v=store.metadata(group,a.space(),study,id,a.actor(),version,input.title(),input.description(),trace.substring(3,35));changed("metadata",v,trace);return value(v);
 }
 @PutMapping("/{assignmentId}/state") ResponseEntity<?> state(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),id=GroupInput.id(assignmentId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long version=expected(r);var input=GroupInput.json(gate.jsonBody(r));
  if(input==null||!input.isObject()||input.size()!=1||!input.path("state").isString()||!Set.of("OPEN","CLOSED").contains(input.path("state").asString()))throw GroupInput.bad();
  var v=store.state(group,a.space(),study,id,a.actor(),version,input.path("state").asString(),trace.substring(3,35));changed("state",v,trace);return value(v);
 }
 @DeleteMapping("/{assignmentId}") ResponseEntity<?> retire(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),id=GroupInput.id(assignmentId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);var v=store.state(group,a.space(),study,id,a.actor(),expected(r),"RETIRED",trace.substring(3,35));changed("retired",v,trace);return value(v);
 }
 @GetMapping("/{assignmentId}/revisions") ResponseEntity<?> history(@PathVariable String groupId,@PathVariable String studyId,@PathVariable String assignmentId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("before","limit"));gate.empty(r);UUID group=GroupInput.id(groupId),study=GroupInput.id(studyId),id=GroupInput.id(assignmentId);long before=gate.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)gate.number(r.getParameter("limit"),5,10);var a=gate.access(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.assignment.reads","type","history").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(group,a.space(),study,id,before,limit));
 }
}
