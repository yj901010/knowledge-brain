package io.knowledgebrain.learning;
import java.util.*;import java.io.IOException;import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.learning.public.enabled",havingValue="true")
@RequestMapping("/api/spaces/{spaceId}")
final class RoadmapAdoptionController {
 private final RoadmapAdoptionStore store;private final RoadmapController browser;private final PublicRoadmapController publicBrowser;private final MeterRegistry metrics;
 RoadmapAdoptionController(RoadmapAdoptionStore store,RoadmapController browser,PublicRoadmapController publicBrowser,MeterRegistry metrics){
  this.store=store;this.browser=browser;this.publicBrowser=publicBrowser;this.metrics=metrics;
 }
 @PostMapping("/roadmap-adoptions") ResponseEntity<?> adopt(@PathVariable String spaceId,HttpServletRequest r)throws IOException{
  RoadmapController.parameters(r,Set.of());UUID space=RoadmapGraph.id(spaceId);String trace=RoadmapController.parent(r);
  UUID actor=browser.authorize(space,r,true,trace);
  var v=browser.body(r,2048);
  if(v.size()!=3||!v.path("sourceSpaceId").isString()||!v.path("sourceRoadmapId").isString()||!v.path("sourceVersion").isIntegralNumber()||!v.path("sourceVersion").canConvertToLong()||v.path("sourceVersion").asLong()<1)throw RoadmapGraph.bad();
  UUID sourceSpace=RoadmapGraph.id(v.path("sourceSpaceId").asString()),sourceId=RoadmapGraph.id(v.path("sourceRoadmapId").asString());
  // Authorize both boundaries freshly using the same credential; claim races fail closed.
  UUID sourceActor=publicBrowser.authorize(sourceSpace,r,"PUBLIC_ROADMAP_READ",trace);
  if(!actor.equals(sourceActor))throw PublicRoadmapStore.error(HttpStatus.CONFLICT,"Principal changed; refresh session");
  var result=store.adopt(space,sourceSpace,sourceId,v.path("sourceVersion").asLong(),actor,trace.substring(3,35));
  metrics.counter("brain.learning.adoption.requests","result",result.created()?"created":"existing").increment();
  org.slf4j.LoggerFactory.getLogger(getClass()).info("roadmap_adoption roadmap_id={} trace_id={}",result.adoption().roadmapId(),trace.substring(3,35));
  return ResponseEntity.status(result.created()?201:200).cacheControl(CacheControl.noStore())
    .header("Location","/api/spaces/"+space+"/roadmaps/"+result.adoption().roadmapId()).body(result.adoption());
 }
 @GetMapping("/roadmaps/{roadmapId}/adoption") ResponseEntity<?> source(@PathVariable String spaceId,@PathVariable String roadmapId,HttpServletRequest r){
  RoadmapController.parameters(r,Set.of());UUID space=RoadmapGraph.id(spaceId),id=RoadmapGraph.id(roadmapId);
  browser.authorize(space,r,false,RoadmapController.parent(r));metrics.counter("brain.learning.adoption.reads").increment();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.source(space,id));
 }
}
