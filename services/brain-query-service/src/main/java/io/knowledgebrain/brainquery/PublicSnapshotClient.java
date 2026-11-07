package io.knowledgebrain.brainquery;
import java.util.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.*;

final class PublicSnapshotClient {
 record Item(UUID entityId,UUID spaceId,long version,String payload){}
 record Page(UUID through,UUID nextAfter,List<Item> items){}
 private final URI origin;private final String key;private final HttpClient http;
 PublicSnapshotClient(String origin,String trust,String key)throws Exception{this.origin=URI.create(origin);this.key=key;if(!this.origin.getScheme().equals("https")||this.origin.getHost()==null||!this.origin.getRawPath().isEmpty()||this.origin.getRawUserInfo()!=null||this.origin.getRawQuery()!=null||this.origin.getRawFragment()!=null||!key.matches("[A-Za-z0-9_-]{43}"))throw new IllegalStateException("Public snapshot configuration invalid");var store=KeyStore.getInstance("PKCS12");store.load(null,null);try(var stream=Files.newInputStream(Path.of(trust))){int i=0;for(var c:CertificateFactory.getInstance("X.509").generateCertificates(stream))store.setCertificateEntry("ca-"+i++,c);}var managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());managers.init(store);var ssl=SSLContext.getInstance("TLS");ssl.init(null,managers.getTrustManagers(),null);http=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();}
 Page page(String kind,UUID after,UUID through){try{if(!Set.of("CONCEPT","RECORD").contains(kind))throw PublicProjection.invalid();String path="/internal/knowledge/public-snapshots?kind="+kind+"&limit=5"+(after==null?"":"&after="+after)+(through==null?"":"&through="+through);var response=http.send(HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+key).GET().build(),info->new NoteSearchClient.BoundedBody());if(response.statusCode()!=200)throw new IllegalStateException("Public snapshot unavailable");var page=PublicProjection.JSON.readValue(response.body(),Page.class);if(page==null||page.items()==null||page.items().size()>5||through!=null&&!through.equals(page.through())||page.through()==null&&(!page.items().isEmpty()||page.nextAfter()!=null))throw PublicProjection.invalid();UUID previous=after;if(page.through()!=null)PublicProjection.id(page.through().toString());for(var item:page.items()){byte[] wire=Base64.getDecoder().decode(item.payload());var e=PublicProjection.decode(wire,item.entityId().toString(),null);if(e==null||kind.equals("CONCEPT")!=e.kind().equals("CONCEPT")||!e.id().equals(item.entityId())||!e.space().equals(item.spaceId())||e.version()!=item.version()||page.through()==null||item.entityId().toString().compareTo(page.through().toString())>0||previous!=null&&item.entityId().toString().compareTo(previous.toString())<=0)throw PublicProjection.invalid();previous=item.entityId();}if(page.nextAfter()!=null&&(page.items().isEmpty()||!page.nextAfter().equals(previous)))throw PublicProjection.invalid();return page;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Public snapshot interrupted");}catch(Exception e){throw new IllegalStateException("Public snapshot unavailable or invalid");}}
}
