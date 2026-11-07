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
@ConditionalOnProperty(name="brain.query.network.enabled",havingValue="true")
class BrainNetworkController {
 record Item(String key,String kind,UUID id,String title,long version,UUID subjectConceptId){}
 record Page(long total,int offset,int limit,Integer nextOffset,List<Item> items){}
 private final BrainProjectionConsumer consumer;private final NoteSearchClient reader;private final SpaceAuthorizationClient authority;private final MeterRegistry metrics;
 BrainNetworkController(BrainProjectionConsumer c,@Qualifier("searchReader")NoteSearchClient r,SpaceAuthorizationClient a,MeterRegistry m){consumer=c;reader=r;authority=a;metrics=m;}
 static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid network request");}
 static int number(String value,int fallback){if(value==null)return fallback;if(!value.matches("0|[1-9][0-9]{0,3}"))throw bad();return Integer.parseInt(value);}
 static void parameters(HttpServletRequest r,Set<String> allowed){if(!r.isSecure()||r.getContentLengthLong()>0||r.getHeader("Transfer-Encoding")!=null)throw bad();for(var e:r.getParameterMap().entrySet())if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw bad();}
 void authorize(String raw,UUID space,HttpServletRequest r,String action){if(!space.toString().equals(raw))throw bad();String token=null;if(r.getCookies()!=null)for(var cookie:r.getCookies())if(cookie.getName().equals("__Host-brain_guest")){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Credential required");token=cookie.getValue();}String trace=r.getHeader("traceparent");if(trace==null||Collections.list(r.getHeaders("traceparent")).size()!=1||!trace.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")||trace.substring(3,35).equals("0".repeat(32))||trace.substring(36,52).equals("0".repeat(16)))trace="00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";authority.authorize(space,token,trace,action);}
 @GetMapping("/api/spaces/{spaceId}/graph/network") ResponseEntity<BrainGraphStore.View> graph(@PathVariable String spaceId,HttpServletRequest r){
  try{parameters(r,Set.of("limit"));var space=BrainProjection.id(spaceId);int limit=number(r.getParameter("limit"),100);if(limit<1||limit>200)throw bad();authorize(spaceId,space,r,"BRAIN_GRAPH");var view=consumer.read(space,limit);metrics.counter("brain.network.requests","type","graph").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(view);}catch(IllegalArgumentException e){throw bad();}catch(GraphReadClient.Unavailable e){throw unavailable();}
 }
 @GetMapping("/api/spaces/{spaceId}/search/network") ResponseEntity<Page> search(@PathVariable String spaceId,HttpServletRequest r){
  try{parameters(r,Set.of("q","offset","limit","kind"));var space=BrainProjection.id(spaceId);int offset=number(r.getParameter("offset"),0),limit=number(r.getParameter("limit"),20);String text=r.getParameter("q"),kind=r.getParameter("kind");NoteSearchClient.brainQuery(space,text,offset,limit,kind);authorize(spaceId,space,r,"BRAIN_SEARCH");if(!consumer.isReady())throw unavailable();var result=page(reader.searchBrain(space,text,offset,limit,kind),space,offset,limit,kind);if(!consumer.isReady())throw unavailable();metrics.counter("brain.network.requests","type","search").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(result);}catch(IllegalArgumentException e){throw bad();}catch(NoteSearchClient.Unavailable e){throw unavailable();}
 }
 static UUID resultId(String raw){try{return BrainProjection.id(raw);}catch(IllegalArgumentException e){throw new NoteSearchClient.Unavailable();}}
 static Page page(JsonNode hits,UUID space,int offset,int limit,String kind){if(hits==null||!hits.path("total").path("value").isIntegralNumber()||!hits.path("total").path("relation").asString().equals("eq")||!hits.path("hits").isArray())throw new NoteSearchClient.Unavailable();long total=hits.path("total").path("value").asLong();var rows=hits.path("hits");if(total<0||rows.size()>limit||rows.size()>Math.max(0,total-offset))throw new NoteSearchClient.Unavailable();var items=new ArrayList<Item>();var seen=new HashSet<String>();for(var h:rows){var s=h.path("_source");String type=s.path("kind").asString(),title=s.path("title").asString();var id=resultId(s.path("entityId").asString());String key=type+":"+id;long version=s.path("canonicalVersion").asLong();if(!List.of("NOTE","CONCEPT","CLAIM","BOOKMARK","QUESTION","DECISION").contains(type)||kind!=null&&!kind.equals(type)||!s.path("entityKey").asString().equals(key)||!seen.add(key)||!s.path("spaceId").asString().equals(space.toString())||!s.path("visibility").asString().equals("PRIVATE")||!s.path("state").asString().equals("ACTIVE")||!s.path("title").isString()||title.isBlank()||title.codePointCount(0,title.length())>650||!s.path("canonicalVersion").isIntegralNumber()||version<1)throw new NoteSearchClient.Unavailable();String subject=s.path("subjectConceptId").asString();if(!s.path("subjectConceptId").isString()||!type.equals("CLAIM")&&!subject.isEmpty())throw new NoteSearchClient.Unavailable();items.add(new Item(key,type,id,title,version,type.equals("CLAIM")?resultId(subject):null));}int next=offset+items.size();return new Page(total,offset,limit,items.size()==limit&&next<total&&next<=1000-limit?next:null,List.copyOf(items));}
 static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Unified network unavailable");}
}
