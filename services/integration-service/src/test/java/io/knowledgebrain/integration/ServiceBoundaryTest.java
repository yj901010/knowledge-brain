package io.knowledgebrain.integration;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;

@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceBoundaryTest {
    @Autowired
    ServletWebServerApplicationContext context;

    @Test
    void servletExecutorUsesVirtualThreads() throws Exception {
        var result = new CompletableFuture<Boolean>();
        var server = (TomcatWebServer) context.getWebServer();
        server.getTomcat().getConnector().getProtocolHandler().getExecutor()
                .execute(() -> result.complete(Thread.currentThread().isVirtual()));
        assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Value("${local.server.port}")
    int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void probesAreAvailableWithoutExposingDetails() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            var response = get(path);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("\"components\"", "\"details\"");
        }
    }

    @Test
    void anonymousDomainAndManagementAccessIsDenied() throws Exception {
        assertThat(get("/api/private").statusCode()).isEqualTo(403);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(403);
    }
}
