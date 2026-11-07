package io.knowledgebrain.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.gateway.api.enabled",havingValue="true")
class GatewayConfiguration {
    static URI origin(String value) {
        URI origin=URI.create(value);
        if(!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getRawUserInfo()!=null || !origin.getRawPath().isEmpty() || origin.getRawQuery()!=null || origin.getRawFragment()!=null)throw new IllegalArgumentException("Fixed HTTPS origin required");
        return origin;
    }
    @Bean GatewayTransport gatewayTransport(@Value("${brain.gateway.identity-origin}") String identity,@Value("${brain.gateway.space-origin}") String space,
            @Value("${brain.gateway.content-origin:https://content-service.brain-apps.svc:8080}") String content,@Value("${brain.gateway.media-origin:https://media-service.brain-apps.svc:8080}") String media,@Value("${brain.gateway.query-origin:https://brain-query-service.brain-apps.svc:8080}") String query,@Value("${brain.gateway.knowledge-origin:https://knowledge-service.brain-apps.svc:8080}") String knowledge,@Value("${brain.gateway.learning-origin:https://learning-service.brain-apps.svc:8080}")String learning,@Value("${brain.gateway.activity-origin:https://activity-service.brain-apps.svc:8080}")String activity,@Value("${brain.gateway.planning-origin:https://planning-service.brain-apps.svc:8080}")String planning,@Value("${brain.gateway.notification-origin:https://notification-service.brain-apps.svc:8080}")String notification,@Value("${brain.gateway.collaboration-origin:https://collaboration-service.brain-apps.svc:8080}")String collaboration,@Value("${brain.gateway.integration-origin:https://integration-service.brain-apps.svc:8080}")String integration,@Value("${brain.gateway.trust-file}") String trustFile) throws Exception {
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(trustFile))) {
            int index=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("backend-ca-"+index++,certificate);
        }
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);
        var client=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new GatewayTransport(client,origin(identity),origin(space),origin(content),origin(media),origin(query),origin(knowledge),origin(learning),origin(activity),origin(planning),origin(notification),origin(collaboration),origin(integration),Duration.ofSeconds(10));
    }
}
