package io.knowledgebrain.learning;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

final class ConceptReferenceClient {
 record Reference(UUID spaceId,UUID conceptId,UUID conceptSpaceId,UUID principalId,long version,String title,String visibility){}
 private final RestClient client;
 ConceptReferenceClient(String origin,String trustFile)throws Exception{
  URI url=URI.create(origin);if(!"https".equals(url.getScheme())||url.getHost()==null||url.getRawUserInfo()!=null||url.getRawQuery()!=null||url.getRawFragment()!=null||!url.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Knowledge origin required");
  var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);try(var input=Files.newInputStream(Path.of(trustFile))){int i=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("knowledge-ca-"+i++,certificate);}var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(3));client=RestClient.builder().baseUrl(origin).requestFactory(requests).build();
 }
 Reference check(UUID space,UUID concept,UUID conceptSpace,UUID actor,long expected,String token,String parent){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session required");
  try{var ref=client.get().uri("/api/spaces/{space}/concept-references/{concept}/learning-reference",space,concept).header("Cookie","__Host-brain_guest="+token).header("traceparent",parent).retrieve().onStatus(s->s.value()==401||s.value()==403||s.value()==404,(request,response)->{throw new ResponseStatusException(response.getStatusCode(),"Concept reference unavailable");}).body(Reference.class);
   if(ref==null||!space.equals(ref.spaceId())||!concept.equals(ref.conceptId())||!actor.equals(ref.principalId())||ref.version()<1||!("PUBLIC".equals(ref.visibility())||("PRIVATE".equals(ref.visibility())&&space.equals(ref.conceptSpaceId()))))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid Concept reference");if(!conceptSpace.equals(ref.conceptSpaceId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Concept reference unavailable");RoadmapGraph.text(ref.title(),"",0);if(ref.version()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"Concept version changed; select latest before linking");return ref;
  }catch(RestClientException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Knowledge reference check unavailable");}
 }
}
