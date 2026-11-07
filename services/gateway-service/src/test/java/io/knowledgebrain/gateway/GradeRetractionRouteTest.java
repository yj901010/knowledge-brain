package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class GradeRetractionRouteTest {
 String id="0190abcd-0000-7000-8000-000000000001",base="/api/groups/"+id+"/studies/"+id+"/assignments/"+id+"/submissions/"+id+"/grade/revisions/";
 @Test void exactGetPutAndBoundedHistoryBelongToCollaboration(){for(var target:new String[]{"1","9223372036854775807"}){assertEquals("collaboration",GatewayRoute.select("GET",base+target+"/retraction").service());assertEquals("collaboration",GatewayRoute.select("PUT",base+target+"/retraction").service());assertEquals("collaboration",GatewayRoute.select("GET",base+target+"/retraction/revisions").service());}}
 @Test void unsupportedMethodsAndNoncanonicalTargetsStayDenied(){for(var method:new String[]{"POST","DELETE","PATCH","HEAD"})assertNull(GatewayRoute.select(method,base+"1/retraction"));for(var tail:new String[]{"0/retraction","01/retraction","1.0/retraction","1/retraction/authors/"+id,"1/retraction/","10000000000000000000/retraction"})assertNull(GatewayRoute.select("GET",base+tail));assertNull(GatewayRoute.select("PUT",base+"1/retraction/revisions"));}
}
