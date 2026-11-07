package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class CourseReviewQueueRouteTest {
 String id="019a0000-0000-7000-8000-000000000001";String path="/api/groups/"+id+"/courses/"+id+"/submissions/review-queue";
 @Test void exactReadOnlyEndpointGoesOnlyToCollaboration(){assertEquals("collaboration",GatewayRoute.select("GET",path).service());for(String method:java.util.List.of("POST","PUT","DELETE"))assertNull(GatewayRoute.select(method,path));}
 @Test void extraSegmentsAndMalformedScopeAreNotProxied(){assertNull(GatewayRoute.select("GET",path+"/all"));assertNull(GatewayRoute.select("GET",path.replace("/courses/"+id,"/courses/invalid")));assertNull(GatewayRoute.select("GET",path.replace("review-queue","review-queue-for-owner")));}
}
