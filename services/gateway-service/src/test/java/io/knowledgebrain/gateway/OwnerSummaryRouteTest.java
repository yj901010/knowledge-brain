package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class OwnerSummaryRouteTest {
 String group="/api/groups/0190abcd-0000-7000-8000-000000000001",course=group+"/courses/0190abcd-0000-7000-8000-000000000002";
 @Test void newPolicyAndSummaryRoutesKeepServiceOwnership(){assertEquals("space",GatewayRoute.select("GET",group+"/progress-sharing-policy").service());assertEquals("space",GatewayRoute.select("PUT",group+"/progress-sharing-policy").service());assertEquals("space",GatewayRoute.select("GET",group+"/progress-sharing-policy/revisions").service());assertEquals("learning",GatewayRoute.select("PUT",course+"/my-progress/owner-share").service());assertEquals("learning",GatewayRoute.select("GET",course+"/shared-progress").service());assertEquals("collaboration",GatewayRoute.select("POST",course+"/progress-sharing-context").service());}
 @Test void arbitraryPeerSelectorsAndUnsupportedMutationsStayClosed(){assertNull(GatewayRoute.select("PUT",course+"/shared-progress"));assertNull(GatewayRoute.select("GET",course+"/my-progress/owner-share/participants/0190abcd-0000-7000-8000-000000000003"));assertNull(GatewayRoute.select("DELETE",group+"/progress-sharing-policy"));assertNull(GatewayRoute.select("PUT",course+"/progress-sharing-context"));}
}
