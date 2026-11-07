package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.*;
class SubmissionFilesRouteTest {
 String id="0190abcd-0000-7000-8000-000000000001",base="/api/groups/"+id+"/studies/"+id+"/assignments/"+id;
 @Test void uploadSubmitReadAndDownloadBelongToCollaboration(){for(String p:List.of("/submission-files","/submission-files/"+id+"/complete","/submission-files/"+id+"/upload-form","/submissions/with-files","/submissions/"+id+"/revisions/1/files/"+id+"/download"))assertEquals("collaboration",GatewayRoute.select("POST",base+p).service());assertEquals("collaboration",GatewayRoute.select("GET",base+"/submissions/"+id+"/revisions/1/files").service());}
 @Test void noInternalMediaOrUnboundedMutationRoute(){assertNull(GatewayRoute.select("POST","/internal/media/domain-files/create"));for(String v:List.of("0","01","-1"))assertNull(GatewayRoute.select("GET",base+"/submissions/"+id+"/revisions/"+v+"/files"));for(String method:List.of("PUT","DELETE","PATCH"))assertNull(GatewayRoute.select(method,base+"/submission-files"));assertNull(GatewayRoute.select("POST",base+"/submission-files/"+id+"/delete"));}
}
