package io.knowledgebrain.collaboration;
import java.net.URI;import java.net.http.HttpClient;import java.nio.file.*;import java.security.KeyStore;import java.security.cert.CertificateFactory;import java.time.*;import java.util.*;import javax.net.ssl.*;
import org.springframework.http.*;import org.springframework.http.client.JdkClientHttpRequestFactory;import org.springframework.web.client.*;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;import tools.jackson.databind.DeserializationFeature;import tools.jackson.core.StreamReadFeature;
final class LearningRoadmapClient {
 record Check(UUID spaceId,UUID roadmapLinkId,String token){@Override public String toString(){return "Check[REDACTED]";}}
 record Reference(UUID principalId,UUID spaceId,UUID roadmapLinkId,long version,String state,UUID sourceSpaceId,UUID sourceRoadmapId,long sourceVersion,String title) {}
 private final RestClient client;private final String key;
 private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 LearningRoadmapClient(RestClient client,String key){this.client=client;this.key=key;}
 LearningRoadmapClient(String url,String trustFile,String key)throws Exception{
        URI endpoint=URI.create(url);
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !endpoint.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Learning origin required");
        if(!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("32-byte service credential required");
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(Path.of(trustFile))) {
            int index=0;for(var certificate:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("learning-ca-"+index++,certificate);
        }
        var factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        var tls=SSLContext.getInstance("TLS");tls.init(null,factory.getTrustManagers(),null);
        var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(5));
        this.client=RestClient.builder().baseUrl(url).requestFactory(requests).build();this.key=key;
 }
 Reference check(UUID space,UUID link,UUID actor,long expected,String token,String trace){
  if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Session required");
  try{return client.post().uri("/internal/learning/group-roadmap-reference").header("Authorization","Bearer "+key).header("traceparent",trace).body(new Check(space,link,token)).exchange((request,response)->{
   int code=response.getStatusCode().value();if(Set.of(401,403,404).contains(code))throw new ResponseStatusException(response.getStatusCode(),"Roadmap reference unavailable");
   if(code!=200||response.getHeaders().getContentType()==null||!response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))throw unavailable();byte[] bytes=response.getBody().readNBytes(16385);if(bytes.length>16384)throw unavailable();
   var value=json.readTree(bytes);if(value==null||!value.isObject()||value.size()!=9)throw unavailable();for(String field:List.of("principalId","spaceId","roadmapLinkId","state","sourceSpaceId","sourceRoadmapId","title"))if(!value.path(field).isString())throw unavailable();for(String field:List.of("version","sourceVersion"))if(!value.path(field).isIntegralNumber()||!value.path(field).canConvertToLong())throw unavailable();Reference ref;
   try{ref=json.treeToValue(value,Reference.class);}catch(RuntimeException e){throw unavailable();}
   if(ref==null||!space.equals(ref.spaceId())||!link.equals(ref.roadmapLinkId())||ref.principalId()==null||!actor.equals(ref.principalId())||ref.sourceSpaceId()==null||ref.sourceRoadmapId()==null||ref.sourceVersion()<1||ref.version()<1||ref.title()==null||ref.title().isBlank()||ref.title().codePointCount(0,ref.title().length())>200||!GroupInput.unicode(ref.title()))throw unavailable();
   for(var id:List.of(ref.principalId(),ref.sourceSpaceId(),ref.sourceRoadmapId()))if(id.version()!=7||id.variant()!=2)throw unavailable();
   if(!Set.of("ACTIVE","RETIRED").contains(ref.state()))throw unavailable();if(!"ACTIVE".equals(ref.state())||ref.version()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"Group roadmap changed or retired; inspect current version");return ref;
  });}catch(ResponseStatusException error){throw error;}catch(RuntimeException error){throw unavailable();}
 }
 private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Learning reference unavailable");}
}
