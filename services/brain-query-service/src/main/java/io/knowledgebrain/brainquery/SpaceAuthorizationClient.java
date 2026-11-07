package io.knowledgebrain.brainquery;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
final class SpaceAuthorizationClient {
 record Check(UUID spaceId,String token,String action) { @Override public String toString(){return "Check[REDACTED]";} }
 record Decision(UUID spaceId,UUID principalId,String action,String visibility) {}
 private final URI origin;private final String key;private final HttpClient http;private final JsonMapper json=JsonMapper.builder().build();
 SpaceAuthorizationClient(String endpoint,String trustFile,String key) throws Exception {
  origin=URI.create(endpoint);
  if(!"https".equals(origin.getScheme()) || origin.getHost()==null || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null || !origin.getRawPath().isEmpty() || !key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("Fixed HTTPS Space origin and query service credential required");
  this.key=key;var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);
  try(var stream=Files.newInputStream(Path.of(trustFile))) {int n=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(stream))trust.setCertificateEntry("ca-"+n++,cert);}
  var manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,manager.getTrustManagers(),null);
  http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 }
 UUID authorize(UUID space,String token,String traceparent) {return authorize(space,token,traceparent,"NOTE_SEARCH");}
 UUID authorize(UUID space,String token,String traceparent,String action) {
  if(!java.util.Set.of("NOTE_SEARCH","NOTE_GRAPH","BRAIN_SEARCH","BRAIN_GRAPH","PUBLIC_SEARCH","PUBLIC_GRAPH").contains(action))throw unavailable();
  if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Guest credential required");
  try {
   var req=HttpRequest.newBuilder(origin.resolve("/internal/space/query-authorization")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+key).header("Content-Type","application/json").header("traceparent",traceparent).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new Check(space,token,action)))).build();
   var response=http.send(req,info->new NoteSearchClient.BoundedBody());
   if(response.statusCode()==401 || response.statusCode()==403 || response.statusCode()==404)throw new ResponseStatusException(HttpStatus.valueOf(response.statusCode()),"Search access unavailable");
   if(response.statusCode()!=200)throw unavailable();
   return validate(json.readValue(response.body(),Decision.class),space,action);
  } catch(ResponseStatusException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(Exception e){throw unavailable();}
 }
 static UUID validate(Decision decision,UUID space) {return validate(decision,space,"NOTE_SEARCH");}
 static UUID validate(Decision decision,UUID space,String action) {
  if(decision==null || !space.equals(decision.spaceId()) || !(action.startsWith("PUBLIC_")?"PUBLIC":"PRIVATE").equals(decision.visibility()) || !action.equals(decision.action()) || decision.principalId()==null || decision.principalId().version()!=7 || decision.principalId().variant()!=2)throw unavailable();return decision.principalId();
 }
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Space authorization unavailable");}
}
