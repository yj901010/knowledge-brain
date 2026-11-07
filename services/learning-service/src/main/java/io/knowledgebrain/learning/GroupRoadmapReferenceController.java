package io.knowledgebrain.learning;
import java.util.*;import java.nio.charset.StandardCharsets;import java.security.MessageDigest;import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;import org.springframework.http.*;
import io.micrometer.core.instrument.MeterRegistry;
@RestController
@ConditionalOnProperty(name="brain.learning.collaboration-reference.enabled",havingValue="true")
final class GroupRoadmapReferenceController {
 record Reference(UUID principalId,UUID spaceId,UUID roadmapLinkId,long version,String state,UUID sourceSpaceId,UUID sourceRoadmapId,long sourceVersion,String title) {}
 private final GroupRoadmapStore store;private final SpaceAuthorizationClient authority;private final RoadmapController browser;private final byte[] key;private final MeterRegistry metrics;
 GroupRoadmapReferenceController(GroupRoadmapStore store,SpaceAuthorizationClient authority,RoadmapController browser,MeterRegistry metrics,@Value("${brain.learning.collaboration-reference.key}")String key){
  if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Dedicated Collaboration reference key required");this.store=store;this.authority=authority;this.browser=browser;this.metrics=metrics;this.key=("Bearer "+key).getBytes(StandardCharsets.US_ASCII);
 }
 @PostMapping("/internal/learning/group-roadmap-reference") ResponseEntity<?> check(HttpServletRequest r)throws IOException{
  var auth=Collections.list(r.getHeaders("Authorization"));
  if(!r.isSecure()||auth.size()!=1||!MessageDigest.isEqual(key,auth.getFirst().getBytes(StandardCharsets.UTF_8)))throw PublicRoadmapStore.error(HttpStatus.UNAUTHORIZED,"Service capability required");
  if(r.getHeader("Cookie")!=null||r.getHeader("Origin")!=null)throw PublicRoadmapStore.error(HttpStatus.FORBIDDEN,"Service capability only");
  RoadmapController.parameters(r,Set.of());var v=browser.body(r,2048);
  if(v.size()!=3||!v.path("spaceId").isString()||!v.path("roadmapLinkId").isString()||!v.path("token").isString())throw RoadmapGraph.bad();
  UUID space=RoadmapGraph.id(v.path("spaceId").asString()),id=RoadmapGraph.id(v.path("roadmapLinkId").asString());String trace=RoadmapController.parent(r);
  UUID principal=authority.authorize(space,v.path("token").asString(),"GROUP_ROADMAP_READ","PRIVATE",trace);
  var link=store.get(space,id);var source=link.source();metrics.counter("brain.learning.group.roadmap.reads","type","collaboration-reference").increment();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Reference(principal,space,id,link.version(),link.state(),source.spaceId(),source.roadmapId(),source.version(),source.title()));
 }
}
