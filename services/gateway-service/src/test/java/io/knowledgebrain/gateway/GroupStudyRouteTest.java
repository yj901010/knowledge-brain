package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GroupStudyRouteTest {
 @Test void onlyDeclaredStudyRoutesReachCollaboration(){String id="01900000-0000-7000-8000-000000000001",root="/api/groups/"+id+"/studies";for(String verb:new String[]{"GET","POST"})assertEquals("collaboration",GatewayRoute.select(verb,root).service());for(String verb:new String[]{"GET","PUT","DELETE"})assertEquals("collaboration",GatewayRoute.select(verb,root+"/"+id).service());assertNotNull(GatewayRoute.select("GET",root+"/"+id+"/revisions"));assertNotNull(GatewayRoute.select("PUT",root+"/"+id+"/state"));assertNull(GatewayRoute.select("POST",root+"/"+id));assertNull(GatewayRoute.select("GET",root+"/"+id+"/progress"));assertNull(GatewayRoute.select("DELETE",root));}
}
