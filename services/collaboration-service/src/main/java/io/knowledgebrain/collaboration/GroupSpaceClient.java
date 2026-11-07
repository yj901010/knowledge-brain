package io.knowledgebrain.collaboration;

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

final class GroupSpaceClient {
    record Grant(UUID groupId,UUID spaceId,String role,String visibility) {}
    record Access(UUID principalId,String kind,java.util.List<Grant> grants) {}
    record Check(String token,java.util.List<UUID> groupIds){@Override public String toString(){return "Check[REDACTED]";}}
    record Provision(String token,UUID groupId){@Override public String toString(){return "Provision[REDACTED]";}}
    record Participations(UUID principalId,String kind,java.util.List<Grant> grants,UUID nextAfter){}
    record PageCheck(String token,UUID after,int limit){@Override public String toString(){return "PageCheck[REDACTED]";}}
    record Credential(String token) { @Override public String toString() { return "Credential[REDACTED]"; } }
    record SharingCheck(String token,UUID groupId,java.util.List<UUID> participantIds){@Override public String toString(){return "SharingCheck[REDACTED]";}}
    record Member(UUID principalId,long membershipVersion) {}
    record Sharing(UUID principalId,UUID groupId,UUID spaceId,String role,long membershipVersion,long policyVersion,boolean enabled,String audience,java.util.List<Member> activeMembers) {}
    private final RestClient client;
    private final String key;
    GroupSpaceClient(String url,String trustFile,String key) throws Exception {
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

    Access access(String token,java.util.List<UUID> groups,String trace){return call("/internal/space/group-access",token,new Check(token,groups),groups,trace);}
    Access provision(String token,UUID group,String trace){return call("/internal/space/group-provision",token,new Provision(token,group),java.util.List.of(group),trace);}
    Access participation(String token,java.util.List<UUID> groups,String trace){return scoped("/internal/space/group-participation",token,new Check(token,groups),groups,trace,true);}
    Participations participations(String token,UUID after,int limit,String trace){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Browser session required");
        try{
            var page=client.post().uri("/internal/space/group-participations").header("Authorization","Bearer "+key).header("traceparent",trace).body(new PageCheck(token,after,limit)).retrieve()
                .onStatus(s->s.value()==401||s.value()==403,(request,response)->{throw new ResponseStatusException(response.getStatusCode(),"Group access unavailable");}).body(Participations.class);
            if(page==null||page.principalId()==null||page.principalId().version()!=7||page.principalId().variant()!=2||!"REGISTERED".equals(page.kind())||page.grants()==null||page.grants().size()>limit)throw unavailable();
            UUID previous=after;for(var grant:page.grants()){if(!validGrant(grant,true)||previous!=null&&grant.groupId().compareTo(previous)<=0)throw unavailable();previous=grant.groupId();}
            if(page.nextAfter()!=null&&(page.grants().size()!=limit||!page.nextAfter().equals(previous)))throw unavailable();return page;
        }catch(RestClientException error){throw unavailable();}
    }
    Sharing sharing(String token,UUID group,java.util.List<UUID> ids,String trace){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Browser session required");
        if(ids==null||ids.size()>100||new java.util.HashSet<>(ids).size()!=ids.size())throw GroupInput.bad();
        try{return client.post().uri("/internal/space/group-progress-sharing").header("Authorization","Bearer "+key).header("traceparent",trace).body(new SharingCheck(token,group,ids)).exchange((request,response)->{
            int code=response.getStatusCode().value();if(java.util.Set.of(401,403,404).contains(code))throw new ResponseStatusException(response.getStatusCode(),"Group sharing unavailable");if(code!=200||response.getHeaders().getContentType()==null||!response.getHeaders().getContentType().isCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))throw unavailable();
            var bytes=response.getBody().readNBytes(16385);if(bytes.length>16384)throw unavailable();var mapper=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();var v=mapper.readTree(bytes);if(v==null||!v.isObject()||v.size()!=9)throw unavailable();
            for(var n:java.util.List.of("principalId","groupId","spaceId","role","audience"))if(!v.path(n).isString())throw unavailable();for(var n:java.util.List.of("membershipVersion","policyVersion"))if(!v.path(n).isIntegralNumber()||!v.path(n).canConvertToLong())throw unavailable();if(!v.path("enabled").isBoolean()||!v.path("activeMembers").isArray()||v.path("activeMembers").size()>ids.size())throw unavailable();for(var m:v.path("activeMembers"))if(!m.isObject()||m.size()!=2||!m.path("principalId").isString()||!m.path("membershipVersion").isIntegralNumber()||!m.path("membershipVersion").canConvertToLong())throw unavailable();
            var a=mapper.treeToValue(v,Sharing.class);for(var id:java.util.List.of(a.principalId(),a.groupId(),a.spaceId()))if(id.version()!=7||id.variant()!=2)throw unavailable();if(!group.equals(a.groupId())||!java.util.Set.of("OWNER","MEMBER","VIEWER").contains(a.role())||a.membershipVersion()<1||a.policyVersion()<0||a.enabled()&&a.policyVersion()<1||!"OWNER".equals(a.audience())||!ids.isEmpty()&&!"OWNER".equals(a.role()))throw unavailable();var seen=new java.util.HashSet<UUID>();for(var m:a.activeMembers())if(!ids.contains(m.principalId())||!seen.add(m.principalId())||m.membershipVersion()<1)throw unavailable();return a;
        });}catch(ResponseStatusException e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    private boolean validGrant(Grant g,boolean member){return g!=null&&g.groupId()!=null&&g.groupId().version()==7&&g.groupId().variant()==2&&g.spaceId()!=null&&g.spaceId().version()==7&&g.spaceId().variant()==2&&(member?java.util.Set.of("OWNER","MEMBER","VIEWER").contains(java.util.Optional.ofNullable(g.role()).orElse("")):"OWNER".equals(g.role()))&&"PRIVATE".equals(g.visibility());}
    private Access call(String path,String token,Object body,java.util.List<UUID> requested,String trace){return scoped(path,token,body,requested,trace,false);}
    private Access scoped(String path,String token,Object body,java.util.List<UUID> requested,String trace,boolean member){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Browser session required");
        try{
            var access=client.post().uri(path).header("Authorization","Bearer "+key).headers(h->{if(trace!=null)h.set("traceparent",trace);}).body(body).retrieve()
                .onStatus(s->s.value()==401||s.value()==403||s.value()==404,(request,response)->{throw new ResponseStatusException(response.getStatusCode(),"Group access unavailable");}).body(Access.class);
            if(access==null||access.principalId()==null||access.principalId().version()!=7||access.principalId().variant()!=2||!"REGISTERED".equals(access.kind())||access.grants()==null||access.grants().size()>requested.size())throw unavailable();
            var seen=new java.util.HashSet<UUID>();
            for(var grant:access.grants())if(!validGrant(grant,member)||!requested.contains(grant.groupId())||!seen.add(grant.groupId()))throw unavailable();
            return access;
        }catch(RestClientException error){throw unavailable();}
    }
    private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Space authorization unavailable");}
}
