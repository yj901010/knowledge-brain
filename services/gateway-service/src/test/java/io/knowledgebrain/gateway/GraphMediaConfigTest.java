package io.knowledgebrain.gateway;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GraphMediaConfigTest {
 @Test void explicitHttpsOriginOnly(){
  assertEquals("",GraphWebController.checkedOrigin(""));
  assertEquals("https://localhost:9443",GraphWebController.checkedOrigin("https://localhost:9443"));
  for(String invalid:new String[]{"http://localhost:9443","https://localhost:9443/","https://localhost:9443/path","https://user@localhost:9443","https://localhost:9443?q=x","https://localhost:9443#x","https://localhost:9443;connect-src *"})assertThrows(RuntimeException.class,()->GraphWebController.checkedOrigin(invalid));
 }
 @Test void configIsPublicOriginOnlyAndNoStore(){var response=new GraphWebController("https://localhost:9443").mediaConfig();assertEquals("no-store",response.getHeaders().getCacheControl());assertEquals(java.util.Map.of("origin","https://localhost:9443"),response.getBody());}
}
