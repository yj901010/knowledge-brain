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
@RequestMapping("/api/groups/{groupId}/studies")
final class StudyController {
 record Access(UUID actor,UUID space,String role) {}
 private final StudyStore store;
 private final GroupStore groups;
 private final GroupSpaceClient authority;
 private final MeterRegistry metrics;
 private final String origin;
 StudyController(StudyStore store,GroupStore groups,GroupSpaceClient authority,MeterRegistry metrics,@Value("${brain.collaboration.browser-origin}")String origin){this.store=store;this.groups=groups;this.authority=authority;this.metrics=metrics;this.origin=origin;}
 String token(HttpServletRequest r){
  if(!r.isSecure())throw GroupInput.bad();var values=new ArrayList<String>();
  for(var header:Collections.list(r.getHeaders("Cookie")))for(var part:header.split(";")){var pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals("__Host-brain_guest"))values.add(pair[1]);}
  if(values.size()!=1||!values.getFirst().matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Browser session required");return values.getFirst();
 }
 String trace(HttpServletRequest r){
  var headers=Collections.list(r.getHeaders("traceparent"));if(headers.size()==1){var p=headers.getFirst();if(p.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")&&!p.substring(3,35).equals("0".repeat(32))&&!p.substring(36,52).equals("0".repeat(16)))return p;}
  return "00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";
 }
 Access access(UUID group,HttpServletRequest r,boolean write,String trace){
  var a=participant(group,r,write,trace);if(write&&!a.role().equals("OWNER"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Group owner required");return a;
 }
 Access participant(UUID group,HttpServletRequest r,boolean write,String trace){
  String token=token(r);
  if(write&&(Collections.list(r.getHeaders("Origin")).size()!=1||!origin.equals(r.getHeader("Origin"))||r.getHeader("Sec-Fetch-Site")!=null&&!"same-origin".equals(r.getHeader("Sec-Fetch-Site"))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
  var a=authority.participation(token,List.of(group),trace);var config=groups.active(List.of(group));
  if(config.size()!=1||a.grants().size()!=1||!a.grants().getFirst().groupId().equals(group)||!a.grants().getFirst().spaceId().equals(config.getFirst().spaceId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Group unavailable");
  return new Access(a.principalId(),config.getFirst().spaceId(),a.grants().getFirst().role());
 }
 void query(HttpServletRequest r,Set<String> allowed){if(r.getQueryString()!=null&&r.getQueryString().length()>256)throw GroupInput.bad();for(var e:r.getParameterMap().entrySet())if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw GroupInput.bad();}
 private byte[] body(HttpServletRequest r)throws IOException{byte[] bytes=r.getInputStream().readNBytes(65537);if(bytes.length>65536)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Study request too large");return bytes;}
 byte[] jsonBody(HttpServletRequest r)throws IOException{if(!MediaType.APPLICATION_JSON_VALUE.equalsIgnoreCase(Optional.ofNullable(r.getContentType()).orElse("").split(";",2)[0].trim()))throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"JSON required");return body(r);}
 void empty(HttpServletRequest r)throws IOException{if(body(r).length!=0)throw GroupInput.bad();}
 long number(String raw,long fallback,long max){if(raw==null)return fallback;try{if(!raw.matches("[1-9][0-9]{0,18}"))throw GroupInput.bad();long v=Long.parseLong(raw);if(v>max)throw GroupInput.bad();return v;}catch(NumberFormatException e){throw GroupInput.bad();}}
 static String tag(long version){return "\"st"+version+"\"";}
 static long expected(HttpServletRequest r){
  var values=Collections.list(r.getHeaders("If-Match"));if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");
  if(values.size()!=1||!values.getFirst().matches("\"st[1-9][0-9]{0,18}\""))throw GroupInput.bad();try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw GroupInput.bad();}
 }
 private ResponseEntity<?> value(StudyStore.Study v){return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);}
 private void changed(String type,StudyStore.Study v,String trace){metrics.counter("brain.collaboration.study.changes","type",type).increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("group_study type={} study_id={} trace_id={}",type,v.studyId(),trace.substring(3,35));}
 @PostMapping ResponseEntity<?> create(@PathVariable String groupId,HttpServletRequest r)throws IOException{
  query(r,Set.of());UUID group=GroupInput.id(groupId);String trace=trace(r);var a=access(group,r,true,trace);
  var keys=Collections.list(r.getHeaders("Idempotency-Key"));if(keys.size()!=1||!keys.getFirst().matches("[0-9a-f]{32}"))throw GroupInput.bad();var input=GroupInput.settings(jsonBody(r));
  var result=store.create(group,a.space(),a.actor(),keys.getFirst(),input.title(),input.description(),trace.substring(3,35));changed(result.created()?"created":"existing",result.study(),trace);
  return ResponseEntity.status(result.created()?201:200).header("Location","/api/groups/"+group+"/studies/"+result.study().studyId()).eTag(tag(result.study().version())).cacheControl(CacheControl.noStore()).body(result.study());
 }
 @GetMapping ResponseEntity<?> list(@PathVariable String groupId,HttpServletRequest r)throws IOException{
  query(r,Set.of("after","limit"));empty(r);UUID group=GroupInput.id(groupId),after=r.getParameter("after")==null?null:GroupInput.id(r.getParameter("after"));int limit=(int)number(r.getParameter("limit"),20,100);var a=access(group,r,false,trace(r));metrics.counter("brain.collaboration.study.reads","type","catalog").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.list(group,a.space(),after,limit));
 }
 @GetMapping("/{studyId}") ResponseEntity<?> get(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  query(r,Set.of());empty(r);UUID group=GroupInput.id(groupId),id=GroupInput.id(studyId);var a=access(group,r,false,trace(r));metrics.counter("brain.collaboration.study.reads","type","current").increment();return value(store.get(group,a.space(),id));
 }
 @PutMapping("/{studyId}") ResponseEntity<?> metadata(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  query(r,Set.of());UUID group=GroupInput.id(groupId),id=GroupInput.id(studyId);String trace=trace(r);var a=access(group,r,true,trace);long version=expected(r);var input=GroupInput.settings(jsonBody(r));
  var v=store.metadata(group,a.space(),id,a.actor(),version,input.title(),input.description(),trace.substring(3,35));changed("metadata",v,trace);return value(v);
 }
 @PutMapping("/{studyId}/state") ResponseEntity<?> state(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  query(r,Set.of());UUID group=GroupInput.id(groupId),id=GroupInput.id(studyId);String trace=trace(r);var a=access(group,r,true,trace);long version=expected(r);var input=GroupInput.json(jsonBody(r));
  if(input==null||!input.isObject()||input.size()!=1||!input.path("state").isString()||!Set.of("ACTIVE","COMPLETED").contains(input.path("state").asString()))throw GroupInput.bad();
  var v=store.state(group,a.space(),id,a.actor(),version,input.path("state").asString(),trace.substring(3,35));changed("state",v,trace);return value(v);
 }
 @DeleteMapping("/{studyId}") ResponseEntity<?> retire(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  query(r,Set.of());empty(r);UUID group=GroupInput.id(groupId),id=GroupInput.id(studyId);String trace=trace(r);var a=access(group,r,true,trace);var v=store.state(group,a.space(),id,a.actor(),expected(r),"RETIRED",trace.substring(3,35));changed("retired",v,trace);return value(v);
 }
 @GetMapping("/{studyId}/revisions") ResponseEntity<?> history(@PathVariable String groupId,@PathVariable String studyId,HttpServletRequest r)throws IOException{
  query(r,Set.of("before","limit"));empty(r);UUID group=GroupInput.id(groupId),id=GroupInput.id(studyId);long before=number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)number(r.getParameter("limit"),5,10);var a=access(group,r,false,trace(r));metrics.counter("brain.collaboration.study.reads","type","history").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.history(group,a.space(),id,before,limit));
 }
}
