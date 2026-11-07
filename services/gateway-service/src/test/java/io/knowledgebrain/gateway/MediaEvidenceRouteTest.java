package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class MediaEvidenceRouteTest {
 @Test void onlyTypedUuidV7EvidenceMethodsAreProxiedAndOwnerReferenceStaysInternal(){String id="019a0808-1234-7000-8000-000000000001",base="/api/spaces/"+id+"/roadmaps/"+id+"/media-evidence";for(String method:new String[]{"GET","POST"})assertEquals("learning",GatewayRoute.select(method,base).service());for(String method:new String[]{"GET","PUT","DELETE"})assertEquals("learning",GatewayRoute.select(method,base+"/"+id).service());assertEquals("learning",GatewayRoute.select("GET",base+"/"+id+"/revisions").service());for(String path:new String[]{base+"/bad",base+"/"+id+"/revisions/extra","/api/spaces/"+id+"/media/"+id+"/learning-reference"})assertNull(GatewayRoute.select("GET",path));assertNull(GatewayRoute.select("PATCH",base+"/"+id));assertNull(GatewayRoute.select("POST",base+"/"+id+"/revisions"));}
}
