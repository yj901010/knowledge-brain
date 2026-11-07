package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SubmissionInstructionsRouteTest {
 String id="0190abcd-0000-7000-8000-000000000001",base="/api/groups/"+id+"/studies/"+id+"/assignments/"+id+"/submissions/"+id+"/revisions/";
 @Test void onlyExactImmutableInstructionReadsReachCollaboration(){for(var version:new String[]{"1","57","9223372036854775807"})assertEquals("collaboration",GatewayRoute.select("GET",base+version+"/instructions").service());for(var method:new String[]{"POST","PUT","DELETE","PATCH","HEAD"})assertNull(GatewayRoute.select(method,base+"1/instructions"));}
 @Test void malformedVersionsSelectorsAndExtraSegmentsRemainDenied(){for(var suffix:new String[]{"0/instructions","01/instructions","-1/instructions","1.0/instructions","1e3/instructions","10000000000000000000/instructions","1/instructions/all","1/"+id+"/instructions","1/instructions/"})assertNull(GatewayRoute.select("GET",base+suffix));}
}
