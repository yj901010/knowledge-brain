package io.knowledgebrain.space;

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

final class IdentityPrincipalClient {
    record Principal(UUID principalId,String kind,Instant expiresAt) {}
    record Credential(String token) { @Override public String toString() { return "Credential[REDACTED]"; } }
    private final RestClient client;
    private final String key;
    IdentityPrincipalClient(String url,String trustFile,String key) throws Exception {
        URI endpoint=URI.create(url);
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !endpoint.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Identity origin required");
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("32-byte service credential required");
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(trustFile))) {
            int index=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("identity-ca-"+index++,certificate);
        }
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);
        var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(5));
        this.client=RestClient.builder().baseUrl(url).requestFactory(requests).build();this.key=key;
    }
    Principal resolve(String token) { return resolve(token,null); }
    Principal resolve(String token,String traceparent) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential");
        try {
            var principal=client.post().uri("/internal/identity/guest-introspection").header("Authorization","Bearer "+key).headers(h->{if(traceparent!=null)h.set("traceparent",traceparent);})
                .body(new Credential(token)).retrieve().onStatus(s->s.value()==401,(request,response)->{throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid guest credential");})
                .body(Principal.class);
            if(principal==null || principal.principalId()==null || principal.principalId().version()!=7 || principal.principalId().variant()!=2 || !("GUEST".equals(principal.kind())||"REGISTERED".equals(principal.kind())) || principal.expiresAt()==null || !principal.expiresAt().isAfter(Instant.now()))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity authentication unavailable");
            return principal;
        } catch(RestClientException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Identity authentication unavailable"); }
    }
}
