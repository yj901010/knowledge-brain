package io.knowledgebrain.media;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.UUID;
import javax.net.ssl.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

final class SpaceAuthorizationClient {
    record Decision(UUID spaceId,UUID principalId,String action,String visibility) {}
    record Check(UUID spaceId,String token,String action) { @Override public String toString() { return "Check[REDACTED]"; } }
    private final RestClient client;
    private final String key;
    SpaceAuthorizationClient(String url,String trustFile,String key) throws Exception {
        URI endpoint=URI.create(url);
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !endpoint.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Space origin required");
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("32-byte service credential required");
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(trustFile))) {
            int index=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("space-ca-"+index++,certificate);
        }
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);
        var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(5));
        this.client=RestClient.builder().baseUrl(url).requestFactory(requests).build();this.key=key;
    }
    UUID authorize(UUID spaceId,String token,boolean write,String traceparent) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential");
        String action=write?"MEDIA_WRITE":"MEDIA_READ";
        try {
            var decision=client.post().uri("/internal/space/media-authorization").header("Authorization","Bearer "+key).header("traceparent",traceparent)
                .body(new Check(spaceId,token,action)).retrieve().onStatus(s->s.value()==401 || s.value()==403 || s.value()==404,(request,response)->{throw new ResponseStatusException(response.getStatusCode(),"Media access unavailable");}).body(Decision.class);
            if(decision==null || !spaceId.equals(decision.spaceId()) || !action.equals(decision.action()) || !"PRIVATE".equals(decision.visibility()) || decision.principalId()==null || decision.principalId().version()!=7 || decision.principalId().variant()!=2)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Space authorization unavailable");
            return decision.principalId();
        } catch(RestClientException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Space authorization unavailable"); }
    }
}
