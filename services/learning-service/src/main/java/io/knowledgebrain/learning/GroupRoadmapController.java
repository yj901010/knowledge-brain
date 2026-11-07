package io.knowledgebrain.learning;
import java.util.*;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.learning.public.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}/group-roadmaps")
final class GroupRoadmapController {
 private final GroupRoadmapStore store;
 private final RoadmapController browser;
 private final PublicRoadmapController publicBrowser;
 private final SpaceAuthorizationClient authority;
 private final MeterRegistry metrics;
 private final String origin;
 GroupRoadmapController(GroupRoadmapStore store,RoadmapController browser,PublicRoadmapController publicBrowser,SpaceAuthorizationClient authority,MeterRegistry metrics,@Value("${brain.learning.browser-origin}")String origin){
  this.store=store;this.browser=browser;this.publicBrowser=publicBrowser;this.authority=authority;this.metrics=metrics;this.origin=origin;
 }
 private UUID authorize(UUID space,HttpServletRequest r,boolean write,String trace){
  if(!r.isSecure())throw RoadmapGraph.bad();
  if(write&&(Collections.list(r.getHeaders("Origin")).size()!=1||!origin.equals(r.getHeader("Origin"))||r.getHeader("Sec-Fetch-Site")!=null&&!r.getHeader("Sec-Fetch-Site").equals("same-origin")))throw PublicRoadmapStore.error(HttpStatus.FORBIDDEN,"Same-origin request required");
  String token=null;if(r.getCookies()!=null)for(var c:r.getCookies())if(c.getName().equals("__Host-brain_guest")){
   if(token!=null)throw PublicRoadmapStore.error(HttpStatus.UNAUTHORIZED,"Duplicate credential");token=c.getValue();
  }
  return authority.authorize(space,token,write?"GROUP_ROADMAP_WRITE":"GROUP_ROADMAP_READ","PRIVATE",trace);
 }
 private ResponseEntity<?> response(Object body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
 static String tag(long version){return "\"gr"+version+"\"";}
 static long expected(HttpServletRequest r){
  var values=Collections.list(r.getHeaders("If-Match"));if(values.isEmpty())throw PublicRoadmapStore.error(HttpStatus.PRECONDITION_REQUIRED,"If-Match required");
  if(values.size()!=1||!values.getFirst().matches("\"gr[1-9][0-9]{0,18}\""))throw RoadmapGraph.bad();
  try{return Long.parseLong(values.getFirst().substring(3,values.getFirst().length()-1));}catch(NumberFormatException e){throw RoadmapGraph.bad();}
 }
 @PostMapping ResponseEntity<?> attach(@PathVariable String spaceId,HttpServletRequest r)throws IOException{
  RoadmapController.parameters(r,Set.of());UUID space=RoadmapGraph.id(spaceId);String trace=RoadmapController.parent(r);UUID actor=authorize(space,r,true,trace);
  var v=browser.body(r,2048);
  if(v.size()!=3||!v.path("sourceSpaceId").isString()||!v.path("sourceRoadmapId").isString()||!v.path("sourceVersion").isIntegralNumber()||!v.path("sourceVersion").canConvertToLong()||v.path("sourceVersion").asLong()<1)throw RoadmapGraph.bad();
  UUID sourceSpace=RoadmapGraph.id(v.path("sourceSpaceId").asString()),sourceId=RoadmapGraph.id(v.path("sourceRoadmapId").asString());
  if(!actor.equals(publicBrowser.authorize(sourceSpace,r,"PUBLIC_ROADMAP_READ",trace)))throw PublicRoadmapStore.error(HttpStatus.CONFLICT,"Principal changed; refresh session");
  var result=store.attach(space,sourceSpace,sourceId,v.path("sourceVersion").asLong(),actor,trace.substring(3,35));
  changed(result.created()?"linked":"existing",result.link(),trace);
  return ResponseEntity.status(result.created()?201:200).header("Location","/api/spaces/"+space+"/group-roadmaps/"+result.link().linkId()).eTag(tag(result.link().version())).cacheControl(CacheControl.noStore()).body(result.link());
 }
 @GetMapping ResponseEntity<?> list(@PathVariable String spaceId,HttpServletRequest r){
  RoadmapController.parameters(r,Set.of("after","limit"));UUID space=RoadmapGraph.id(spaceId),after=r.getParameter("after")==null?null:RoadmapGraph.id(r.getParameter("after"));
  int limit=(int)RoadmapController.number(r.getParameter("limit"),20,100);authorize(space,r,false,RoadmapController.parent(r));metrics.counter("brain.learning.group.roadmap.reads","type","catalog").increment();return response(store.list(space,after,limit));
 }
 @GetMapping("/{linkId}") ResponseEntity<?> get(@PathVariable String spaceId,@PathVariable String linkId,HttpServletRequest r){
  RoadmapController.parameters(r,Set.of());UUID space=RoadmapGraph.id(spaceId),id=RoadmapGraph.id(linkId);authorize(space,r,false,RoadmapController.parent(r));
  var v=store.get(space,id);metrics.counter("brain.learning.group.roadmap.reads","type","current").increment();return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);
 }
 @DeleteMapping("/{linkId}") ResponseEntity<?> retire(@PathVariable String spaceId,@PathVariable String linkId,HttpServletRequest r){
  RoadmapController.parameters(r,Set.of());if(r.getContentLengthLong()>0||r.getHeader("Transfer-Encoding")!=null)throw RoadmapGraph.bad();
  UUID space=RoadmapGraph.id(spaceId),id=RoadmapGraph.id(linkId);String trace=RoadmapController.parent(r);UUID actor=authorize(space,r,true,trace);
  var v=store.retire(space,id,expected(r),actor,trace.substring(3,35));changed("retired",v,trace);return ResponseEntity.ok().eTag(tag(v.version())).cacheControl(CacheControl.noStore()).body(v);
 }
 @GetMapping("/{linkId}/revisions") ResponseEntity<?> history(@PathVariable String spaceId,@PathVariable String linkId,HttpServletRequest r){
  RoadmapController.parameters(r,Set.of("before","limit"));UUID space=RoadmapGraph.id(spaceId),id=RoadmapGraph.id(linkId);
  long before=RoadmapController.number(r.getParameter("before"),Long.MAX_VALUE,Long.MAX_VALUE);int limit=(int)RoadmapController.number(r.getParameter("limit"),5,10);
  authorize(space,r,false,RoadmapController.parent(r));metrics.counter("brain.learning.group.roadmap.reads","type","history").increment();return response(store.history(space,id,before,limit));
 }
 private void changed(String type,GroupRoadmapStore.Link link,String trace){metrics.counter("brain.learning.group.roadmap.changes","type",type).increment();org.slf4j.LoggerFactory.getLogger(getClass()).info("group_roadmap type={} link_id={} trace_id={}",type,link.linkId(),trace.substring(3,35));}
}
