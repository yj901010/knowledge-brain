package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GroupRoadmapRouteTest {
 @Test void explicitLearningRoutesNeverExposeProgressOrArbitraryWrites(){
  String id="01900000-0000-7000-8000-000000000001",root="/api/spaces/"+id+"/group-roadmaps";
  for(String path:new String[]{root,root+"/"+id,root+"/"+id+"/revisions"})assertEquals("learning",GatewayRoute.select("GET",path).service());
  assertNotNull(GatewayRoute.select("POST",root));assertNotNull(GatewayRoute.select("DELETE",root+"/"+id));
  assertNull(GatewayRoute.select("PUT",root+"/"+id));assertNull(GatewayRoute.select("POST",root+"/"+id));assertNull(GatewayRoute.select("GET",root+"/"+id+"/learning"));assertNull(GatewayRoute.select("GET",root+"/"+id+"/evidence"));
 }
}
