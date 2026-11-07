package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RoadmapAdoptionRouteTest {
 @Test void adoptionAndProvenanceOnlyHaveExactPrivateRoutes(){
  String id="0199e000-0000-7000-8000-000000000007",base="/api/spaces/"+id;
  assertEquals("learning",GatewayRoute.select("POST",base+"/roadmap-adoptions").service());
  assertEquals("learning",GatewayRoute.select("GET",base+"/roadmaps/"+id+"/adoption").service());
  for(String method:new String[]{"GET","PUT","DELETE"})assertNull(GatewayRoute.select(method,base+"/roadmap-adoptions"));
  for(String method:new String[]{"POST","PUT","DELETE"})assertNull(GatewayRoute.select(method,base+"/roadmaps/"+id+"/adoption"));
  assertNull(GatewayRoute.select("GET","/api/public/spaces/"+id+"/roadmaps/"+id+"/adoption"));
 }
}
