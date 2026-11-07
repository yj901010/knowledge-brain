package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ReviewEventTargetRouteTest {
 String id="019a0000-0000-7000-8000-000000000001";String path="/api/groups/"+id+"/studies/"+id+"/assignments/"+id+"/submissions/"+id+"/review-events/"+id;
 @Test void exactReadOnlySourceRoute(){assertEquals("collaboration",GatewayRoute.select("GET",path).service());assertNull(GatewayRoute.select("POST",path));assertNull(GatewayRoute.select("PUT",path));assertNull(GatewayRoute.select("DELETE",path));}
 @Test void malformedEventAndExtraSegmentsNeverProxy(){assertNull(GatewayRoute.select("GET",path+"/reason"));assertNull(GatewayRoute.select("GET",path.substring(0,path.length()-id.length())+"not-an-event"));assertNull(GatewayRoute.select("GET",path.replace("/review-events/","/review-events/all/")));}
}
