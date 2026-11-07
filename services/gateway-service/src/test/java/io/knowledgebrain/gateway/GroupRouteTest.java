package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GroupRouteTest {
 String id="0199fdf2-5678-7000-8000-123456789abc";
 @Test void onlyExactGroupRoutesAreExposed(){for(String path:new String[]{"/api/groups","/api/groups/"+id,"/api/groups/"+id+"/revisions"})assertEquals("collaboration",GatewayRoute.select("GET",path).service());assertEquals("collaboration",GatewayRoute.select("POST","/api/groups").service());assertEquals("collaboration",GatewayRoute.select("POST","/api/groups/"+id+"/complete").service());for(String path:new String[]{"/internal/space/group-provision","/api/groups/../spaces","/api/groups/"+id.toUpperCase(),"/api/groups/"+id+"/complete"})assertNull(GatewayRoute.select("GET",path));for(String method:new String[]{"DELETE","PUT","PATCH"})assertNull(GatewayRoute.select(method,"/api/groups/"+id));}
}
