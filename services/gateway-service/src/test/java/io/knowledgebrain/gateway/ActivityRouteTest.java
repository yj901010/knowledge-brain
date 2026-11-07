package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ActivityRouteTest {
 @Test void timelineHasOnlyExactReadRouteAndCanonicalServicesKeepTheirBoundary(){var id="019a0808-1234-7000-8000-000000000001";var path="/api/spaces/"+id+"/activity/timeline";assertEquals("activity",GatewayRoute.select("GET",path).service());for(var method:new String[]{"POST","PUT","DELETE","PATCH"})assertNull(GatewayRoute.select(method,path));for(var suffix:new String[]{"/","/rebuild","/export","/other"})assertNull(GatewayRoute.select("GET",path+suffix));assertNull(GatewayRoute.select("GET","/internal/learning/activity-events"));assertEquals("learning",GatewayRoute.select("GET","/api/spaces/"+id+"/roadmaps").service());}
}
