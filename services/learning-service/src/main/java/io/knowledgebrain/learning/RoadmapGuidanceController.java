package io.knowledgebrain.learning;

import java.util.Set;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.learning.api.enabled",havingValue="true")
final class RoadmapGuidanceController {
 private final RoadmapGuidanceStore store;private final RoadmapController authority;private final MeterRegistry metrics;
 RoadmapGuidanceController(RoadmapGuidanceStore store,RoadmapController authority,MeterRegistry metrics){this.store=store;this.authority=authority;this.metrics=metrics;}
 @GetMapping("/api/spaces/{spaceId}/roadmaps/{roadmapId}/learning-guidance")
 ResponseEntity<RoadmapGuidanceRules.Guidance> guidance(@PathVariable String spaceId,@PathVariable String roadmapId,HttpServletRequest request)throws IOException{
  RoadmapController.parameters(request,Set.of());if(request.getInputStream().read()!=-1)throw RoadmapGraph.bad();var space=RoadmapGraph.id(spaceId);var roadmap=RoadmapGraph.id(roadmapId);
  authority.authorize(space,request,false,RoadmapController.parent(request));
  var guidance=store.read(space,roadmap);metrics.counter("brain.learning.records","operation","guidance-read").increment();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(guidance);
 }
}
