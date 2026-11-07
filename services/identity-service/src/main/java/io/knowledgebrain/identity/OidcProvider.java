package io.knowledgebrain.identity;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.web.server.ResponseStatusException;

class OidcProvider {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(OidcProvider.class);
    final String issuer,clientId,callback,publicOrigin;
    private final String secret;private final RestClient client;private final JwtDecoder decoder;
    record Tokens(String access,String refresh,Instant accessExpires,Instant sessionExpires,String subject) {
        @Override public String toString(){return "Tokens[REDACTED]";}
    }
    OidcProvider(String publicOrigin,String backend,String trustFile,String clientId,String secret,String callback) throws Exception {
        origin(publicOrigin);origin(backend);URI cb=URI.create(callback);if(!"https".equals(cb.getScheme())||cb.getHost()==null||cb.getRawUserInfo()!=null||cb.getRawQuery()!=null||cb.getRawFragment()!=null||!"/api/identity/login/callback".equals(cb.getPath()))throw new IllegalArgumentException("Exact HTTPS callback required");
        this.publicOrigin=publicOrigin;this.issuer=publicOrigin+"/realms/brain-users";this.clientId=clientId;this.secret=secret;this.callback=callback;
        if(!clientId.matches("[a-z0-9-]{1,64}")||secret==null||secret.length()<32)throw new IllegalArgumentException("Confidential login client required");
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);try(var input=Files.newInputStream(Path.of(trustFile))){int i=0;for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(input))trust.setCertificateEntry("keycloak-"+i++,cert);}
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);
        var transport=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();var requests=new JdkClientHttpRequestFactory(transport);requests.setReadTimeout(Duration.ofSeconds(5));
        String base=backend+"/realms/brain-users/protocol/openid-connect";client=RestClient.builder().baseUrl(base).requestFactory(requests).build();
        var jwt=NimbusJwtDecoder.withJwkSetUri(base+"/certs").restOperations(new RestTemplate(requests)).build();
        jwt.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),(Jwt token)->token.getAudience().contains(clientId)?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));decoder=jwt;
    }
    // Package test constructor: the production constructor always installs strict TLS/signature validation.
    OidcProvider(String issuer,String clientId,String callback){this.issuer=issuer;this.publicOrigin=issuer.substring(0,issuer.indexOf("/realms/"));this.clientId=clientId;this.callback=callback;secret=null;client=null;decoder=null;}
    String authorization(String state,String nonce,String challenge){return issuer+"/protocol/openid-connect/auth?"+form(Map.of("client_id",clientId,"response_type","code","scope","openid","redirect_uri",callback,"state",state,"nonce",nonce,"code_challenge",challenge,"code_challenge_method","S256","prompt","login"));}
    Tokens exchange(String code,String verifier,String nonce) {
        var result=post("/token",Map.of("grant_type","authorization_code","code",code,"redirect_uri",callback,"code_verifier",verifier));
        try{var id=decoder.decode(string(result,"id_token"));if(!nonce.equals(id.getClaimAsString("nonce"))||id.getSubject()==null||id.getSubject().isBlank()||(id.getAudience().size()>1&&!clientId.equals(id.getClaimAsString("azp"))))throw GuestSessionService.unauthorized();return tokens(result,id.getSubject());}
        catch(JwtException e){log.warn("oidc_id_token rejected failure={}",e.getClass().getSimpleName());if(e instanceof JwtValidationException validation)for(var error:validation.getErrors())log.warn("oidc_validation code={} description={}",error.getErrorCode(),error.getDescription());throw GuestSessionService.unauthorized();}
    }
    Tokens refresh(String refresh,String subject){return tokens(post("/token",Map.of("grant_type","refresh_token","refresh_token",refresh)),subject);}
    void active(String access,String subject) {
        var result=post("/token/introspect",Map.of("token",access));
        if(!Boolean.TRUE.equals(result.get("active"))||!subject.equals(result.get("sub"))||!issuer.equals(result.get("iss"))||!clientId.equals(result.get("client_id"))){log.warn("oidc_introspection rejected active={} subject_match={} issuer_match={} client_match={}",Boolean.TRUE.equals(result.get("active")),subject.equals(result.get("sub")),issuer.equals(result.get("iss")),clientId.equals(result.get("client_id")));throw GuestSessionService.unauthorized();}
    }
    void revoke(String refresh){post("/revoke",Map.of("token",refresh,"token_type_hint","refresh_token"));}
    private Tokens tokens(Map<String,Object> result,String subject){String access=string(result,"access_token"),refresh=string(result,"refresh_token");active(access,subject);long life=seconds(result,"expires_in"),session=seconds(result,"refresh_expires_in");return new Tokens(access,refresh,Instant.now().plusSeconds(life),Instant.now().plusSeconds(Math.min(session,43200)),subject);}
    @SuppressWarnings("unchecked") private Map<String,Object> post(String path,Map<String,String> fields) {
        var body=new LinkedMultiValueMap<String,String>();fields.forEach(body::add);body.add("client_id",clientId);body.add("client_secret",secret);
        try{var result=client.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body).retrieve().body(Map.class);return result==null?Map.of():result;}
        catch(HttpClientErrorException e){log.warn("oidc_backchannel rejected endpoint={} status={}",path,e.getStatusCode().value());throw GuestSessionService.unauthorized();}
        catch(RestClientException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Registered authentication unavailable");}
    }
    private static String string(Map<String,Object> values,String key){Object value=values.get(key);if(!(value instanceof String text)||text.isEmpty()||text.length()>32768)throw GuestSessionService.unauthorized();return text;}
    private static long seconds(Map<String,Object> values,String key){Object value=values.get(key);if(!(value instanceof Number n)||n.longValue()<=0||n.longValue()>604800)throw GuestSessionService.unauthorized();return n.longValue();}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),java.nio.charset.StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    static void origin(String text){URI uri=URI.create(text);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!uri.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS origin required");}
}
