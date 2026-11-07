package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class DecisionRoutesTest {
 @Test void onlyDeclaredPrivateDecisionOperationsReachContent(){String id="01a10000-0000-7000-8000-000000000000",base="/api/spaces/"+id+"/decisions";for(String method:new String[]{"GET","POST"})assertEquals("content",GatewayRoute.select(method,base).service());for(String method:new String[]{"GET","PUT","DELETE"})assertEquals("content",GatewayRoute.select(method,base+"/"+id).service());assertEquals("content",GatewayRoute.select("GET",base+"/"+id+"/revisions").service());assertNull(GatewayRoute.select("DELETE",base));assertNull(GatewayRoute.select("POST",base+"/"+id));assertNull(GatewayRoute.select("PUT",base+"/"+id+"/revisions"));assertNull(GatewayRoute.select("GET",base+"/preview"));assertNull(GatewayRoute.select("POST","/internal/space/content-authorization"));}
}
