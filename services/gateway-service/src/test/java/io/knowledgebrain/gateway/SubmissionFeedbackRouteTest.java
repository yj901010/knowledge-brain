package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SubmissionFeedbackRouteTest {
 String id="0190abcd-0000-7000-8000-000000000001",base="/api/groups/"+id+"/studies/"+id+"/assignments/"+id+"/submissions/"+id+"/feedback";
 @Test void feedbackRoutesBelongToCollaboration(){assertEquals("collaboration",GatewayRoute.select("GET",base).service());assertEquals("collaboration",GatewayRoute.select("PUT",base).service());assertEquals("collaboration",GatewayRoute.select("GET",base+"/revisions").service());}
 @Test void unsupportedPeerSelectorsAndMethodsRemainDenied(){assertNull(GatewayRoute.select("POST",base));assertNull(GatewayRoute.select("DELETE",base));assertNull(GatewayRoute.select("PUT",base+"/revisions"));assertNull(GatewayRoute.select("GET",base+"/authors/"+id));}
}
