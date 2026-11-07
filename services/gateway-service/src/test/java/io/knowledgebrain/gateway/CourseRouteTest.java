package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class CourseRouteTest {
 @Test void exactCourseAndCurriculumRoutes(){String id="01900000-0000-7000-8000-000000000001",base="/api/groups/"+id+"/courses",item=base+"/"+id;for(String method:new String[]{"GET","POST"})assertEquals("collaboration",GatewayRoute.select(method,base).service());for(String method:new String[]{"GET","PUT","DELETE"})assertNotNull(GatewayRoute.select(method,item));assertNotNull(GatewayRoute.select("PUT",item+"/curriculum"));assertNotNull(GatewayRoute.select("PUT",item+"/state"));assertNotNull(GatewayRoute.select("GET",item+"/revisions"));assertNull(GatewayRoute.select("POST",item+"/curriculum"));assertNull(GatewayRoute.select("PUT",base));assertNull(GatewayRoute.select("DELETE",base));assertNull(GatewayRoute.select("POST",item+"/revisions"));}
}
