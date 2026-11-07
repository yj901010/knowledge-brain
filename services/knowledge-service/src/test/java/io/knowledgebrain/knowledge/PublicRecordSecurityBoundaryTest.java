package io.knowledgebrain.knowledge;
import java.net.*;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThat;
@TestExecutionListeners(listeners=DependencyInjectionTestExecutionListener.class,inheritListeners=false)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,classes={Application.class,PublicRecordSecurityBoundaryTest.Fixtures.class},properties={"brain.knowledge.public.enabled=true","brain.knowledge.api.enabled=false","brain.knowledge.persistence.enabled=false","brain.knowledge.browser-origin=https://localhost:8443"})
class PublicRecordSecurityBoundaryTest {
    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean PublicRecordStore publicStore(){return mock(PublicRecordStore.class);}
        @Bean ContributionStore contributions(){return mock(ContributionStore.class);}
        @Bean ConceptStore concepts(){return mock(ConceptStore.class);}
        @Bean ConceptController browser(){return mock(ConceptController.class);}
        @Bean SpaceAuthorizationClient space(){return mock(SpaceAuthorizationClient.class);}
    }
    @Value("${local.server.port}") int port;
    HttpResponse<String> request(String method,String path)throws Exception{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}
    @Test void declaredPublicReadsReachTheirOwnHttpsAuthorityGuard()throws Exception{
        String base="/api/public/spaces/0199e000-0000-7000-8000-000000000002";
        for(String path:new String[]{"/explore?limit=20","/records?limit=20","/record-proposals?limit=20","/concepts/0199e000-0000-7000-8000-000000000003/relations?limit=20"})assertThat(request("GET",base+path).statusCode()).as(path+" must reach controller HTTPS guard, not be blocked by the route filter").isEqualTo(400);
    }
    @Test void searchWritesAndUnrelatedManagementStayDenied()throws Exception{assertThat(request("POST","/api/public/spaces/0199e000-0000-7000-8000-000000000002/explore").statusCode()).isEqualTo(403);assertThat(request("GET","/actuator/env").statusCode()).isEqualTo(403);assertThat(request("GET","/api/spaces/0199e000-0000-7000-8000-000000000002/concepts").statusCode()).isEqualTo(403);}
}
