package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RecurrenceRouteTest {
 @Test void onlyDeclaredRecurrenceOperationsAreBrowserReachable(){String id="01a10000-0000-7000-8000-000000000000",base="/api/spaces/"+id+"/recurrences";for(String path:new String[]{base,base+"/"+id,base+"/"+id+"/revisions",base+"/"+id+"/cancellation"})assertEquals("planning",GatewayRoute.select("GET",path).service());for(String path:new String[]{base,base+"/preview",base+"/"+id+"/cancel"})assertEquals("planning",GatewayRoute.select("POST",path).service());assertNull(GatewayRoute.select("PUT",base+"/"+id));assertNull(GatewayRoute.select("DELETE",base+"/"+id));assertNull(GatewayRoute.select("GET",base+"/preview"));assertNull(GatewayRoute.select("GET","/internal/planning/activity-events"));}
}
