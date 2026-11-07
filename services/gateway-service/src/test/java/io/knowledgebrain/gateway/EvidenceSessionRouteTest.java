package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class EvidenceSessionRouteTest {
 @Test void typedEvidenceSessionRoutesAllowOnlyReadWriteAndReadHistory(){String id="019a0808-1234-7000-8000-000000000001",root="/api/spaces/"+id+"/roadmaps/"+id;for(String kind:new String[]{"evidence","concept-evidence","media-evidence"}){var path=root+"/"+kind+"/"+id+"/study-session";assertEquals("learning",GatewayRoute.select("GET",path).service());assertEquals("learning",GatewayRoute.select("PUT",path).service());assertEquals("learning",GatewayRoute.select("GET",path+"/revisions").service());for(String method:new String[]{"POST","DELETE","PATCH"})assertNull(GatewayRoute.select(method,path));assertNull(GatewayRoute.select("PUT",path+"/revisions"));}assertNull(GatewayRoute.select("GET",root+"/unknown/"+id+"/study-session"));}
}
