package io.knowledgebrain.collaboration;
import java.net.URI;import java.net.http.HttpClient;import java.nio.file.*;import java.security.KeyStore;import java.security.cert.CertificateFactory;import java.time.*;import java.util.*;import javax.net.ssl.*;
import org.springframework.http.*;import org.springframework.http.client.JdkClientHttpRequestFactory;import org.springframework.web.client.*;import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;import tools.jackson.databind.json.JsonMapper;import tools.jackson.core.StreamReadFeature;
final class SubmissionMediaClient {
 private final RestClient client;private final String key;
 private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 SubmissionMediaClient(RestClient client,String key){this.client=client;this.key=key;}
 SubmissionMediaClient(String url,String trustFile,String key)throws Exception{
        URI endpoint=URI.create(url);
        if(!"https".equals(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getRawUserInfo()!=null || endpoint.getRawQuery()!=null || endpoint.getRawFragment()!=null || !endpoint.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS Media origin required");
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

 JsonNode call(String operation,UUID space,UUID context,UUID owner,UUID media,String requestKey,JsonNode upload,String trace){
  var body=new LinkedHashMap<String,Object>();body.put("spaceId",space);body.put("contextId",context);body.put("ownerId",owner);if(operation.equals("create")){body.put("key",requestKey);body.put("upload",upload);}else body.put("mediaId",media);
  try{return client.post().uri("/internal/media/domain-files/"+operation).header("Authorization","Bearer "+key).header("traceparent",trace).contentType(MediaType.APPLICATION_JSON).body(body).exchange((request,response)->{
   int code=response.getStatusCode().value();if(Set.of(400,404,409,413).contains(code))throw new ResponseStatusException(response.getStatusCode(),"File unavailable or changed");if(code!=200||response.getHeaders().getContentType()==null||!response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON))throw unavailable();byte[] bytes=response.getBody().readNBytes(16385);if(bytes.length>16384)throw unavailable();JsonNode n;try{n=json.readTree(bytes);}catch(RuntimeException e){throw unavailable();}if(n==null||!n.isObject())throw unavailable();
   if(operation.equals("create")){if(n.size()!=2||!n.has("reference")||!n.has("upload"))throw unavailable();file(n.path("reference"),space,context,owner,null,false);if(!n.path("upload").isNull())form(n.path("upload"));}
   else if(Set.of("get","complete").contains(operation))file(n,space,context,owner,media,operation.equals("complete"));
   else if(operation.equals("upload"))form(n);else if(operation.equals("download")){if(n.size()!=2||!n.path("url").isString()||!n.path("expiresAt").isString())throw unavailable();url(n.path("url").asString());expiry(n.path("expiresAt").asString());}else throw unavailable();return n;
  });}catch(RestClientException e){throw unavailable();}
 }
 SubmissionFilesStore.File file(JsonNode n,UUID space,UUID context,UUID owner,UUID id,boolean available){try{
  if(!n.isObject()||n.size()!=3||!n.path("contextId").isString()||!context.toString().equals(n.path("contextId").asString())||!n.path("ownerId").isString()||!owner.toString().equals(n.path("ownerId").asString()))throw unavailable();var m=n.path("media");if(!m.isObject()||m.size()!=10)throw unavailable();for(String f:List.of("mediaId","spaceId","state","filename","mimeType","sha256","createdAt"))if(!m.path(f).isString())throw unavailable();for(String f:List.of("version","byteSize"))if(!m.path(f).isIntegralNumber()||!m.path(f).canConvertToLong())throw unavailable();UUID media=GroupInput.id(m.path("mediaId").asString());if(id!=null&&!id.equals(media)||!space.toString().equals(m.path("spaceId").asString()))throw unavailable();String state=m.path("state").asString();long version=m.path("version").asLong();Instant.parse(m.path("createdAt").asString());if(state.equals("PENDING")){if(version!=1||!m.path("completedAt").isNull()||available)throw new ResponseStatusException(HttpStatus.CONFLICT,"Upload is not complete");}else if(state.equals("AVAILABLE")){if(version!=2||!m.path("completedAt").isString())throw unavailable();Instant.parse(m.path("completedAt").asString());}else throw unavailable();
  var result=new SubmissionFilesStore.File(media,2,m.path("filename").asString(),m.path("mimeType").asString(),m.path("byteSize").asLong(),m.path("sha256").asString());SubmissionFilesStore.validate(result);return result;
 }catch(ResponseStatusException e){if(e.getStatusCode().value()==409)throw e;throw unavailable();}catch(RuntimeException e){throw unavailable();}}
 static void url(String raw){try{var u=URI.create(raw);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawFragment()!=null)throw unavailable();}catch(IllegalArgumentException e){throw unavailable();}}
 private static void expiry(String raw){try{var t=Instant.parse(raw);if(t.isBefore(Instant.now())||t.isAfter(Instant.now().plusSeconds(901)))throw unavailable();}catch(RuntimeException e){throw unavailable();}}
 private static void form(JsonNode n){if(!n.isObject()||n.size()!=3||!n.path("url").isString()||!n.path("expiresAt").isString()||!n.path("fields").isObject()||n.path("fields").size()>30||n.path("fields").size()<1)throw unavailable();for(var v:n.path("fields"))if(!v.isString())throw unavailable();url(n.path("url").asString());expiry(n.path("expiresAt").asString());}
 private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Media service unavailable");}
}
