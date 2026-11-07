package io.knowledgebrain.brainquery;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;

/** Confined operator validation only; browser cookies and private titles never enter logs. */
public final class GraphRuntimeValidation {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private static HttpClient http;private static final String ORIGIN="https://gateway-service.brain-apps.svc:8080";
 private record Response(int status,Map<String,Object> data,String cookie){}
 private record Browser(String cookie,UUID space){}
 private static void check(boolean okay){if(!okay)throw new IllegalStateException("Graph runtime assertion failed");}
 @SuppressWarnings("unchecked") private static Response call(String path,String method,Map<String,Object> data,String cookie,Long version)throws Exception{
  var builder=HttpRequest.newBuilder(URI.create(ORIGIN+path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json").header("Origin","https://localhost:8443").header("Sec-Fetch-Site","same-origin");
  if(cookie!=null)builder.header("Cookie",cookie);if(version!=null)builder.header("If-Match","\""+version+"\"");
  builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(data)));
  var result=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  Map<String,Object> value=result.body().isBlank()?Map.of():JSON.readValue(result.body(),Map.class);
  return new Response(result.statusCode(),value,result.headers().firstValue("Set-Cookie").map(s->s.split(";")[0]).orElse(null));
 }
 private static Browser browser()throws Exception{
  var identity=call("/api/identity/guest-session","POST",Map.of(),null,null);check(identity.status()==201 && identity.cookie()!=null);
  for(int i=0;i<90;i++){var result=call("/api/spaces/personal","GET",null,identity.cookie(),null);if(result.status()==200)return new Browser(identity.cookie(),UUID.fromString((String)result.data().get("spaceId")));check(result.status()==202);Thread.sleep(1000);}
  throw new IllegalStateException("Space provisioning deadline");
 }
 private static UUID create(Browser b,String title,String body)throws Exception{var result=call("/api/spaces/"+b.space()+"/notes","POST",Map.of("title",title,"markdown",body),b.cookie(),null);check(result.status()==201);return UUID.fromString((String)result.data().get("noteId"));}
 private static void change(Browser b,UUID note,long version,String title,String body)throws Exception{check(call("/api/spaces/"+b.space()+"/notes/"+note,"PUT",Map.of("title",title,"markdown",body),b.cookie(),version).status()==200);}
 private static void retire(Browser b,UUID note,long version)throws Exception{check(call("/api/spaces/"+b.space()+"/notes/"+note,"DELETE",null,b.cookie(),version).status()==204);}
 private static void await(NoteGraphStore graph,UUID space,Predicate<NoteGraphStore.View> expected)throws Exception{
  for(int i=0;i<180;i++){try{if(expected.test(graph.read(space,200)))return;}catch(RuntimeException ignored){}Thread.sleep(1000);}
  throw new IllegalStateException("Graph convergence deadline");
 }
 private static void marker(String name)throws Exception{for(int i=0;i<1200;i++){if(Files.exists(Path.of("/tmp/"+name)))return;Thread.sleep(1000);}throw new IllegalStateException("Graph validation handshake deadline");}
 public static void main(String[] args){
  try{
   var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);try(var in=Files.newInputStream(Path.of("/public-trust/ca.crt"))){int n=0;for(var c:CertificateFactory.getInstance("X.509").generateCertificates(in))trust.setCertificateEntry("ca-"+n++,c);}
   var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var tls=SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);
   http=HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
   String p12=GraphConfiguration.truststore("/graph-projector");
   try(var graph=GraphConfiguration.open("brain-cassandra.brain-data.svc","/graph-projector",p12)){
    var own=browser();var other=browser();String suffix=UUID.randomUUID().toString();String targetTitle="GraphTarget "+suffix;
    UUID source=create(own,"GraphSource "+suffix,"[["+targetTitle+"]]");
    await(graph,own.space(),v->v.nodes().size()==1 && v.links().isEmpty());
    UUID target=create(own,targetTitle,"target body");
    await(graph,own.space(),v->v.nodes().size()==2 && v.links().size()==1 && v.links().getFirst().sourceNoteId().equals(source) && v.links().getFirst().targetNoteId().equals(target));
    var original=graph.read(own.space(),200).links().getFirst().linkId();
    check(((Number)call("/api/spaces/"+own.space()+"/notes/"+source,"GET",null,own.cookie(),null).data().get("version")).longValue()==1);
    UUID foreign=create(other,targetTitle,"other private note");
    await(graph,other.space(),v->v.nodes().size()==1);check(graph.read(own.space(),200).nodes().stream().noneMatch(n->n.noteId().equals(foreign)));
    change(own,target,1,"Renamed "+suffix,"renamed target");
    await(graph,own.space(),v->v.links().size()==1 && v.links().getFirst().linkId().equals(original) && v.nodes().stream().anyMatch(n->n.noteId().equals(target) && n.title().startsWith("Renamed ")));
    System.out.println("PASS actual HTTPS Content -> Outbox -> Kafka -> graph Inbox -> JanusGraph; unresolved/resolved, independent link revision, stable target ID and private Space isolation");
    UUID maximum=create(own,"Maximum links fixture",("[[note:"+target+"]] ").repeat(256));
    await(graph,own.space(),v->v.links().size()==257 && v.links().stream().map(NoteGraphStore.Link::linkId).distinct().count()==257);
    retire(own,maximum,1);await(graph,own.space(),v->v.nodes().size()==2 && v.links().size()==1);
    System.out.println("PASS maximum 256 link occurrences project atomically with distinct stable IDs; retirement clears their edges");
    long sourceVersion=1;
    if("true".equals(System.getenv("BRAIN_GRAPH_VALIDATION_FAILURES"))){
     System.out.println("GRAPH_PROJECTOR_OUTAGE_READY");marker("projector.down");
     change(own,source,1,"Offline source","[[note:"+target+"]] [[note:"+target+"]]");
     check(graph.read(own.space(),200).links().size()==1);System.out.println("GRAPH_PROJECTOR_OFFLINE_WRITE_DONE");marker("projector.up");
     await(graph,own.space(),v->v.links().size()==2);sourceVersion=2;
     System.out.println("PASS replacement Query Pod replays both streams written while graph consumer was stopped");
     System.out.println("GRAPH_STORAGE_OUTAGE_READY");marker("cassandra.down");
     change(own,source,2,"Storage offline source","[[note:"+target+"]]");
     // The operator coordinator asserts actual Pod readiness; existing Gateway keep-alive connections may still serve independent search.
     System.out.println("GRAPH_STORAGE_OFFLINE_WRITE_DONE");marker("cassandra.up");
     graph.close();
     try(var recovered=GraphConfiguration.open("brain-cassandra.brain-data.svc","/graph-projector",p12)){await(recovered,own.space(),v->v.links().size()==1 && v.nodes().stream().anyMatch(n->n.noteId().equals(source) && n.title().equals("Storage offline source")));}sourceVersion=3;
     System.out.println("PASS Cassandra outage makes Query unready; pending Kafka input converges after storage replacement");
    }
    if("true".equals(System.getenv("BRAIN_GRAPH_VALIDATION_REBUILD"))){
     graph.close();System.out.println("GRAPH_CANONICAL_REBUILD_READY");marker("rebuild.scanned");
     change(own,source,sourceVersion,"Concurrent rebuild source","[[note:"+target+"]] [[note:"+target+"]]");sourceVersion++;
     System.out.println("GRAPH_CANONICAL_CONCURRENT_WRITE_DONE");marker("rebuild.complete");
     // Old graph schema/session may be cached across a destructive derived-keyspace rebuild.
     try(var fresh=GraphConfiguration.open("brain-cassandra.brain-data.svc","/graph-projector",p12)){await(fresh,own.space(),v->v.links().size()==2 && v.nodes().stream().anyMatch(n->n.noteId().equals(source) && n.title().equals("Concurrent rebuild source")));}
     System.out.println("PASS canonical graph reset/rebuild recovers fixtures and replays concurrent two-stream writes");
    }
    retire(own,target,2);
    // Open a fresh session after optional keyspace reset; no stale schema ID reuse.
    try(var fresh=GraphConfiguration.open("brain-cassandra.brain-data.svc","/graph-projector",p12)){
     await(fresh,own.space(),v->v.nodes().size()==1 && v.links().isEmpty());retire(own,source,sourceVersion);await(fresh,own.space(),v->v.nodes().isEmpty() && v.links().isEmpty());
    }
    System.out.println("PASS actual target/source retirement removes visible graph nodes/edges without stale-event resurrection");
   }
  }catch(Exception e){System.out.println("FAIL graph runtime validation: "+e.getClass().getSimpleName());System.exit(1);}
 }
}
