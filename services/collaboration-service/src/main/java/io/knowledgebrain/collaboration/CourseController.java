package io.knowledgebrain.collaboration;

import java.util.*;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
@RequestMapping("/api/groups/{groupId}/courses")
final class CourseController {
 private final CourseStore store;private final StudyController gate;private final MeterRegistry metrics;
 CourseController(CourseStore store,StudyController gate,MeterRegistry metrics){this.store=store;this.gate=gate;this.metrics=metrics;}
 static String tag(long version){return "\"co"+version+"\"";}
 static long expected(HttpServletRequest r){
  var values=Collections.list(r.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");
  if(values.size()!=1||!values.getFirst().matches("\"co[1-9][0-9]{0,18}\""))throw GroupInput.bad();try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw GroupInput.bad();}
 }
 private ResponseEntity<?> value(CourseStore.Course v){return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);}
 private void changed(String type,CourseStore.Course v,String trace){metrics.counter("brain.collaboration.course.changes","type",type).increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("group_course type={} course_id={} trace_id={}",type,v.courseId(),trace.substring(3,35));}
 @PostMapping ResponseEntity<?> create(@PathVariable String groupId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);
  var keys=Collections.list(r.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw GroupInput.bad();var input=GroupInput.settings(gate.jsonBody(r));
  var result=store.create(group,a.space(),a.actor(),keys.getFirst(),input.title(),input.description(),trace.substring(3,35));changed(result.created()?"created":"existing",result.course(),trace);
  return ResponseEntity.status(result.created()?201:200).header("Location","/api/groups/"+group+"/courses/"+result.course().courseId()).eTag(tag(result.course().version())).cacheControl(CacheControl.noStore()).body(result.course());
 }
 @GetMapping ResponseEntity<?> list(@PathVariable String groupId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("after","limit"));gate.empty(r);UUID group=GroupInput.id(groupId),after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)gate.number(r.getParameter("limit"),20,100);var a=gate.access(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.course.reads","type","catalog").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(group,a.space(),after,limit));
 }
 @GetMapping("/{courseId}") ResponseEntity<?> get(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),id=GroupInput.id(courseId);var a=gate.access(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.course.reads","type","current").increment();return value(store.get(group,a.space(),id));
 }
 @PutMapping("/{courseId}") ResponseEntity<?> metadata(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),id=GroupInput.id(courseId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long version=expected(r);var input=GroupInput.settings(gate.jsonBody(r));
  var v=store.metadata(group,a.space(),id,a.actor(),version,input.title(),input.description(),trace.substring(3,35));changed("metadata",v,trace);return value(v);
 }
 @PutMapping("/{courseId}/state") ResponseEntity<?> state(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),id=GroupInput.id(courseId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long version=expected(r);var input=GroupInput.json(gate.jsonBody(r));
  if(input==null||!input.isObject()||input.size()!=1||!input.path("state").isString()||!Set.of("ACTIVE","COMPLETED").contains(input.path("state").asString()))throw GroupInput.bad();
  var v=store.state(group,a.space(),id,a.actor(),version,input.path("state").asString(),trace.substring(3,35));changed("state",v,trace);return value(v);
 }
 @DeleteMapping("/{courseId}") ResponseEntity<?> retire(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());gate.empty(r);UUID group=GroupInput.id(groupId),id=GroupInput.id(courseId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);var v=store.state(group,a.space(),id,a.actor(),expected(r),"RETIRED",trace.substring(3,35));changed("retired",v,trace);return value(v);
 }
 @PutMapping("/{courseId}/curriculum") ResponseEntity<?> curriculum(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of());UUID group=GroupInput.id(groupId),id=GroupInput.id(courseId);String trace=gate.trace(r);var a=gate.access(group,r,true,trace);long version=expected(r);var refs=CourseStore.curriculum(gate.jsonBody(r));var v=store.curriculum(group,a.space(),id,a.actor(),version,refs,trace.substring(3,35));changed("curriculum",v,trace);return value(v);
 }
 @GetMapping("/{courseId}/revisions") ResponseEntity<?> history(@PathVariable String groupId,@PathVariable String courseId,HttpServletRequest r)throws IOException{
  gate.query(r,Set.of("before","limit"));gate.empty(r);UUID group=GroupInput.id(groupId),id=GroupInput.id(courseId);long before=gate.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)gate.number(r.getParameter("limit"),3,5);var a=gate.access(group,r,false,gate.trace(r));metrics.counter("brain.collaboration.course.reads","type","history").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(group,a.space(),id,before,limit));
 }
}
