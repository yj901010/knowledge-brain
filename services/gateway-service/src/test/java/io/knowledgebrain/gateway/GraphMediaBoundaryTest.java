package io.knowledgebrain.gateway;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(classes=GraphMediaBoundaryTest.TestApp.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"brain.gateway.api.enabled=true","brain.gateway.media-public-origin=https://localhost:9443"})
@ActiveProfiles("media-boundary-test")
class GraphMediaBoundaryTest {
 @Configuration(proxyBeanMethods=false) @Profile("media-boundary-test") @EnableAutoConfiguration @Import({SecurityConfiguration.class,GraphWebController.class}) static class TestApp {}
 @Value("${local.server.port}") int port;
 final HttpClient client=HttpClient.newHttpClient();
 HttpResponse<String> request(String method,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}
 @Test void onlyGetPublicConfigIsAllowed()throws Exception{var config=request("GET","/graph/media-config");assertEquals(200,config.statusCode());assertEquals("{\"origin\":\"https://localhost:9443\"}",config.body());assertEquals("no-store",config.headers().firstValue("cache-control").orElse(""));assertEquals(403,request("POST","/graph/media-config").statusCode());assertEquals(403,request("GET","/api/private").statusCode());assertEquals(403,request("GET","/actuator/env").statusCode());}
 @Test void pageAllowsOnlyConfiguredStorageConnection()throws Exception{var page=request("GET","/graph");assertEquals(200,page.statusCode());var csp=page.headers().firstValue("content-security-policy").orElse("");assertTrue(csp.contains("connect-src 'self' https://localhost:9443;"));assertTrue(csp.contains("object-src 'none'"));assertTrue(csp.contains("form-action 'self'"));assertFalse(csp.contains("connect-src *"));}
}
