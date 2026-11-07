package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SubmissionRouteTest {
 @Test void onlyExactSubmissionAndReadHistoryRoutesReachCollaboration(){String id="01900000-0000-7000-8000-000000000001",root="/api/groups/"+id+"/studies/"+id+"/assignments/"+id+"/submissions";for(String method:new String[]{"GET","POST"})assertEquals("collaboration",GatewayRoute.select(method,root).service());for(String path:new String[]{root+"/mine",root+"/"+id,root+"/"+id+"/revisions"}){assertEquals("collaboration",GatewayRoute.select("GET",path).service());for(String method:new String[]{"POST","PUT","DELETE","PATCH"})assertNull(GatewayRoute.select(method,path));}assertNull(GatewayRoute.select("GET",root+"/all"));assertNull(GatewayRoute.select("GET",root+"/mine/revisions"));assertNull(GatewayRoute.select("DELETE",root));}
}
