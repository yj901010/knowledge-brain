package io.knowledgebrain.brainquery;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

@RestController
@ConditionalOnProperty(name="brain.query.public.enabled",havingValue="true")
final class PublicDiscoveryController {
 record Page(long projectedTotal,int offset,int limit,Integer nextOffset,int staleOmitted,List<PublicCanonicalClient.Item> items){}
 record Graph(List<PublicCanonicalClient.Item> nodes,List<PublicGraphStore.Edge> edges,boolean truncated,int staleOmitted){}
 private final PublicProjectionConsumer consumer;private final NoteSearchClient reader;private final SpaceAuthorizationClient authority;private final PublicCanonicalClient canonical;private final MeterRegistry metrics;private final PublicSearchCache cache;
 PublicDiscoveryController(PublicProjectionConsumer c,@Qualifier("searchReader")NoteSearchClient r,SpaceAuthorizationClient a,PublicCanonicalClient k,MeterRegistry m,PublicSearchCache cache){consumer=c;reader=r;authority=a;canonical=k;metrics=m;this.cache=cache;}
 private String authorize(UUID space,HttpServletRequest r,String action){String token=null;if(r.getCookies()!=null)for(var cookie:r.getCookies())if(cookie.getName().equals("__Host-brain_guest")){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Credential required");token=cookie.getValue();}String trace=r.getHeader("traceparent");if(trace==null||Collections.list(r.getHeaders("traceparent")).size()!=1||!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")||trace.substring(3,35).equals("0".repeat(32))||trace.substring(36,52).equals("0".repeat(16)))trace="00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";authority.authorize(space,token,trace,action);if(!consumer.isReady())throw unavailable();return trace;}
 @GetMapping("/api/public/spaces/{spaceId}/search")
 ResponseEntity<Page> search(@PathVariable String spaceId,HttpServletRequest r){
  try{
   BrainNetworkController.parameters(r,Set.of("q","kind","offset","limit"));
   UUID space=PublicProjection.id(spaceId);
   int offset=BrainNetworkController.number(r.getParameter("offset"),0),limit=BrainNetworkController.number(r.getParameter("limit"),20);
   String q=r.getParameter("q"),kind=r.getParameter("kind");
   var query=new PublicSearchCache.Query(space,q,kind,offset,limit);
   String trace=authorize(space,r,"PUBLIC_SEARCH");
   var candidates=cache.load(query,()->{
    var hits=reader.searchPublic(space,q,offset,limit,kind);
    var projected=projected(hits,space,kind,offset,limit);
    return new PublicSearchCache.Page(hits.path("total").path("value").asLong(),projected.entrySet().stream()
      .map(p->new PublicSearchCache.Candidate(p.getKey(),p.getValue().kind(),p.getValue().version())).toList());
   });
   // Never cache this check or the response: current authority/version/context applies even on a hit.
   var checked=canonical.check(space,candidates.items().stream().map(PublicSearchCache.Candidate::id).toList(),trace);
   var items=new ArrayList<PublicCanonicalClient.Item>();int omitted=0;
   for(var candidate:candidates.items()){
    var row=checked.get(candidate.id());
    if(current(row,candidate.kind(),candidate.version()))items.add(row);else omitted++;
   }
   int next=offset+candidates.items().size();
   Integer cursor=candidates.items().size()==limit&&next<candidates.total()&&next+limit<=1000?next:null;
   if(!consumer.isReady())throw unavailable();
   metrics.counter("brain.public.requests","type","search").increment();
   return response(new Page(candidates.total(),offset,limit,cursor,omitted,List.copyOf(items)));
  }catch(IllegalArgumentException e){throw BrainNetworkController.bad();}
  catch(NoteSearchClient.Unavailable|GraphReadClient.Unavailable e){throw unavailable();}
 }
 record Projected(String kind,long version){}
 static LinkedHashMap<UUID,Projected> projected(JsonNode hits,UUID space,String kind,int offset,int limit){if(hits==null||!hits.path("total").path("value").isIntegralNumber()||!hits.path("total").path("value").canConvertToLong()||!hits.path("total").path("relation").asString().equals("eq")||!hits.path("hits").isArray())throw new NoteSearchClient.Unavailable();long total=hits.path("total").path("value").asLong();if(total<0||hits.path("hits").size()>limit||hits.path("hits").size()>Math.max(0,total-offset))throw new NoteSearchClient.Unavailable();var result=new LinkedHashMap<UUID,Projected>();for(var hit:hits.path("hits")){var s=hit.path("_source");UUID id;try{id=PublicProjection.id(s.path("entityId").asString());}catch(RuntimeException e){throw new NoteSearchClient.Unavailable();}String type=s.path("kind").asString();if(!hit.path("_id").asString().equals(id.toString())||!PublicProjection.KINDS.contains(type)||kind!=null&&!kind.equals(type)||!s.path("spaceId").asString().equals(space.toString())||!s.path("visibility").asString().equals("PUBLIC")||!s.path("state").asString().equals("ACTIVE")||!s.path("canonicalVersion").isIntegralNumber()||!s.path("canonicalVersion").canConvertToLong()||s.path("canonicalVersion").asLong()<1||result.putIfAbsent(id,new Projected(type,s.path("canonicalVersion").asLong()))!=null)throw new NoteSearchClient.Unavailable();}return result;}
 static boolean current(PublicCanonicalClient.Item row,String kind,long version){return row!=null&&row.state().equals("ACTIVE")&&row.kind().equals(kind)&&row.version()==version&&row.currentContext();}
 @GetMapping("/api/public/spaces/{spaceId}/graph") ResponseEntity<Graph> graph(@PathVariable String spaceId,HttpServletRequest r){try{BrainNetworkController.parameters(r,Set.of("center","depth","limit"));UUID space=PublicProjection.id(spaceId),center=r.getParameter("center")==null?null:PublicProjection.id(r.getParameter("center"));int depth=BrainNetworkController.number(r.getParameter("depth"),2),limit=BrainNetworkController.number(r.getParameter("limit"),80);if(depth<1||depth>3||limit<1||limit>100)throw PublicProjection.invalid();String trace=authorize(space,r,"PUBLIC_GRAPH");var view=consumer.read(space,center,depth,limit);var checked=canonical.check(space,view.nodes().stream().map(PublicGraphStore.Node::id).toList(),trace);var nodes=new ArrayList<PublicCanonicalClient.Item>();var ids=new HashSet<UUID>();for(var node:view.nodes()){var row=checked.get(node.id());if(current(row,node.kind(),node.version())){nodes.add(row);ids.add(row.entityId());}}var edges=view.edges().stream().filter(e->ids.contains(e.source())&&ids.contains(e.target())).toList();if(!consumer.isReady())throw unavailable();metrics.counter("brain.public.requests","type","graph").increment();return response(new Graph(List.copyOf(nodes),edges,view.truncated(),view.nodes().size()-nodes.size()));}catch(IllegalArgumentException e){throw BrainNetworkController.bad();}catch(NoteSearchClient.Unavailable|GraphReadClient.Unavailable e){throw unavailable();}}
 static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Public discovery unavailable");}
 private static <T> ResponseEntity<T> response(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(body);}
}
