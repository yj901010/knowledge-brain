package io.knowledgebrain.learning;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import javax.net.ssl.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

final class GitHubReferenceClient {
 record Reference(UUID spaceId,UUID principalId,UUID repositoryId,UUID commitId,String repositoryFullName,String sha,String subject,String authorLogin,String committedAt,String importedAt,String url,String visibility,String sourceVisibility,long version){}

 private final RestClient client;
 GitHubReferenceClient(String origin,String trustFile)throws Exception{
  URI url=URI.create(origin);if(!"https".equals(url.getScheme())||url.getHost()==null||url.getRawUserInfo()!=null||url.getRawQuery()!=null||url.getRawFragment()!=null||!url.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Integration origin required");
  var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);try(var input=Files.newInputStream(Path.of(trustFile))){int i=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("content-ca-"+i++,certificate);}var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(3));client=RestClient.builder().baseUrl(origin).requestFactory(requests).build();
 }
 Reference check(UUID space,UUID repo,UUID commit,UUID actor,String token,String parent){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session required");
  try{var ref=client.get().uri("/api/spaces/{space}/github/repositories/{repo}/commits/{commit}/reference",space,repo,commit).header("Cookie","__Host-brain_guest="+token).header("traceparent",parent).retrieve().onStatus(s->s.value()==401||s.value()==403||s.value()==404,(request,response)->{throw new ResponseStatusException(response.getStatusCode(),"GitHub reference unavailable");}).body(Reference.class);
   validate(ref,space,repo,commit,actor);return ref;
  }catch(RestClientException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Integration reference unavailable");}
 }
 static void validate(Reference ref,UUID space,UUID repo,UUID commit,UUID actor){try{if(ref==null||!space.equals(ref.spaceId())||!repo.equals(ref.repositoryId())||!commit.equals(ref.commitId())||!actor.equals(ref.principalId())||ref.version()!=1||!"PRIVATE".equals(ref.visibility())||!"PUBLIC".equals(ref.sourceVisibility())||!ref.repositoryFullName().matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")||!ref.sha().matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")||!ref.url().equals("https://github.com/"+ref.repositoryFullName()+"/commit/"+ref.sha())||ref.subject().codePointCount(0,ref.subject().length())>500||ref.subject().codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xd800&&c<=0xdfff)||ref.authorLogin()!=null&&!ref.authorLogin().matches("[A-Za-z0-9-]+(?:\\[bot\\])?"))throw new IllegalArgumentException();Instant.parse(ref.committedAt());Instant.parse(ref.importedAt());}catch(RuntimeException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid Integration reference");}}
}
