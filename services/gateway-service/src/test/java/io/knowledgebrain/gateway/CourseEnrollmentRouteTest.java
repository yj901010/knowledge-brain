package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class CourseEnrollmentRouteTest {
 @Test void onlySelfWritesAndReadRoutesAreExposed(){String id="01900000-0000-7000-8000-000000000001",base="/api/groups/"+id+"/courses/"+id;for(String tail:new String[]{"/enrollment","/enrollment/revisions","/enrollments","/enrollments/"+id,"/enrollments/"+id+"/revisions"})assertEquals("collaboration",GatewayRoute.select("GET",base+tail).service());assertNotNull(GatewayRoute.select("PUT",base+"/enrollment"));for(String method:new String[]{"POST","DELETE","PATCH"})assertNull(GatewayRoute.select(method,base+"/enrollment"));assertNull(GatewayRoute.select("PUT",base+"/enrollments/"+id));assertNull(GatewayRoute.select("POST",base+"/enrollments"));}
}
