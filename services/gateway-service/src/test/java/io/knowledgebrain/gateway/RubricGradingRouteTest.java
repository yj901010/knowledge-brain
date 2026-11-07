package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class RubricGradingRouteTest {
 String id="0190abcd-0000-7000-8000-000000000001",base="/api/groups/"+id+"/studies/"+id+"/assignments/"+id+"/submissions/"+id;
 @Test void calculatedGradeAndImmutableBasisRoutesBelongToCollaboration(){for(String method:java.util.List.of("GET","PUT"))assertEquals("collaboration",GatewayRoute.select(method,base+"/rubric-grade").service());assertEquals("collaboration",GatewayRoute.select("GET",base+"/grade/revisions/1/rubric-assessment").service());}
 @Test void noHistoryMutationsOrPeerSelectors(){for(String method:java.util.List.of("PUT","POST","DELETE"))assertNull(GatewayRoute.select(method,base+"/grade/revisions/1/rubric-assessment"));assertNull(GatewayRoute.select("POST",base+"/rubric-grade"));assertNull(GatewayRoute.select("GET",base+"/grade/revisions/01/rubric-assessment"));assertNull(GatewayRoute.select("GET",base+"/rubric-grade/actors/"+id));}
}
