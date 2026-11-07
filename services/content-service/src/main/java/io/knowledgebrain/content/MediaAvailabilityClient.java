package io.knowledgebrain.content;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;
final class MediaAvailabilityClient {
 record File(UUID mediaId,UUID spaceId,String state,long version,String filename,String mimeType,long byteSize,String sha256,Instant createdAt,Instant completedAt){}
 record Check(UUID spaceId,List<UUID> mediaIds){}
 private final RestClient client;private final String key;
 MediaAvailabilityClient(String origin,String trustFile,String key) throws Exception {var endpoint=URI.create(origin);if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !endpoint.getRawPath().isEmpty() || !key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Fixed authenticated HTTPS Media origin required");var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);try(var in=Files.newInputStream(Path.of(trustFile))){int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(in))trust.setCertificateEntry("media-"+n++,cert);}var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);var http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();var requests=new JdkClientHttpRequestFactory(http);requests.setReadTimeout(Duration.ofSeconds(5));client=RestClient.builder().baseUrl(origin).requestFactory(requests).build();this.key=key;}
 List<File> check(UUID space,List<UUID> ids,String parent){if(ids.isEmpty())return List.of();try{var files=client.post().uri("/internal/media/attachment-check").header("Authorization","Bearer "+key).header("traceparent",parent).body(new Check(space,ids)).retrieve().onStatus(s->s.value()==404 || s.value()==409,(req,res)->{throw new ResponseStatusException(res.getStatusCode(),"Media unavailable for attachment");}).body(File[].class);if(files==null || files.length!=ids.size())throw unavailable();for(int i=0;i<files.length;i++){var f=files[i];if(!ids.get(i).equals(f.mediaId()) || !space.equals(f.spaceId()) || !"AVAILABLE".equals(f.state()) || f.version()<2 || f.sha256()==null || !f.sha256().matches("[0-9a-f]{64}") || f.filename()==null || f.mimeType()==null || f.byteSize()<1 || f.byteSize()>10485760)throw unavailable();}return List.of(files);}catch(RestClientException e){throw unavailable();}}
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Media authority unavailable");}
}
