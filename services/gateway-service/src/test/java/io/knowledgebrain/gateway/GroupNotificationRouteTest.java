package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GroupNotificationRouteTest {
 String group="019a0000-0000-7000-8000-000000000001",id="019a0000-0000-7000-8000-000000000002";
 @Test void exactGroupRoutesGoOnlyToNotification(){for(var path:java.util.List.of("/notifications","/notifications/"+id,"/notifications/"+id+"/revisions","/notification-preference","/notification-preference/revisions"))assertEquals("notification",GatewayRoute.select("GET","/api/groups/"+group+path).service());for(var method:java.util.List.of("PUT","DELETE"))assertEquals("notification",GatewayRoute.select(method,"/api/groups/"+group+"/notifications/"+id).service());assertEquals("notification",GatewayRoute.select("PUT","/api/groups/"+group+"/notification-preference").service());}
 @Test void noArbitraryRecipientsOrMachineExportRoutes(){assertNull(GatewayRoute.select("POST","/api/groups/"+group+"/notifications"));assertNull(GatewayRoute.select("GET","/api/groups/"+group+"/notifications/recipients/"+id));assertNull(GatewayRoute.select("DELETE","/api/groups/"+group+"/notification-preference"));assertNull(GatewayRoute.select("GET","/internal/collaboration/review-signals"));assertNull(GatewayRoute.select("GET","/api/groups/"+group+"/notifications/"+id+"/payload"));}
}
