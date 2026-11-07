package io.knowledgebrain.brainquery;
import java.security.SecureRandom;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
@RestController
@ConditionalOnProperty(name="brain.query.api.enabled",havingValue="true")
class NoteSearchController {
 record Item(UUID noteId,String title) {}
 record Page(long total,int offset,int limit,Integer nextOffset,List<Item> items) {}
 private final SpaceAuthorizationClient authority;private final NoteSearchClient reader;private final MeterRegistry metrics;private final SecureRandom random=new SecureRandom();
 NoteSearchController(SpaceAuthorizationClient authority,@Qualifier("searchReader") NoteSearchClient reader,MeterRegistry metrics){this.authority=authority;this.reader=reader;this.metrics=metrics;}
 @GetMapping("/api/spaces/{spaceId}/search/notes") ResponseEntity<Page> search(@PathVariable UUID spaceId,HttpServletRequest request) {
  var timing=io.micrometer.core.instrument.Timer.start(metrics);
  try {
   if(spaceId.version()!=7 || spaceId.variant()!=2 || !request.isSecure())throw bad();
   for(var entry:request.getParameterMap().entrySet())if(!Set.of("q","offset","limit").contains(entry.getKey()) || entry.getValue().length!=1)throw bad();
   String q=request.getParameter("q");int offset=number(request.getParameter("offset"),0),limit=number(request.getParameter("limit"),20);
   try{NoteSearchProjection.query(spaceId,q,offset,limit);}catch(IllegalArgumentException e){throw bad();}
   String token=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if("__Host-brain_guest".equals(cookie.getName())){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Guest credential required");token=cookie.getValue();}
   if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Guest credential required");
   String trace=traceparent(request);authority.authorize(spaceId,token,trace);
   var page=page(reader.search(spaceId,q,offset,limit),offset,limit);
   metrics.counter("brain.search.requests","outcome","success").increment();
   return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("X-Trace-Id",trace.substring(3,35)).body(page);
  }catch(ResponseStatusException e){metrics.counter("brain.search.requests","outcome","denied_or_unavailable").increment();throw e;}
  catch(NoteSearchClient.Unavailable | IllegalArgumentException e){metrics.counter("brain.search.requests","outcome","storage_unavailable").increment();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Search unavailable");}
  finally{timing.stop(metrics.timer("brain.search.request.duration"));}
 }
 static Page page(JsonNode hits,int offset,int limit) {
  if(hits==null || !hits.path("total").path("value").isIntegralNumber() || !"eq".equals(hits.path("total").path("relation").asString()) || !hits.path("hits").isArray())throw new NoteSearchClient.Unavailable();
  long total=hits.path("total").path("value").asLong();var entries=hits.path("hits");
  if(total<0 || entries.size()>limit || entries.size()>Math.max(0,total-offset))throw new NoteSearchClient.Unavailable();
  var items=new ArrayList<Item>();var used=new HashSet<UUID>();
  for(var hit:entries){var source=hit.path("_source");if(!source.path("noteId").isString() || !source.path("title").isString())throw new NoteSearchClient.Unavailable();var id=UUID.fromString(source.path("noteId").asString());var title=source.path("title").asString();if(id.version()!=7 || id.variant()!=2 || !id.toString().equals(source.path("noteId").asString()) || !used.add(id) || title.isBlank() || title.codePointCount(0,title.length())>200)throw new NoteSearchClient.Unavailable();items.add(new Item(id,title));}
  int next=offset+items.size();return new Page(total,offset,limit,items.size()==limit && next<total && next<=1000-limit?next:null,List.copyOf(items));
 }
 private static int number(String value,int fallback){if(value==null)return fallback;if(!value.matches("0|[1-9][0-9]{0,3}"))throw bad();return Integer.parseInt(value);}
 private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid search request");}
 private String traceparent(HttpServletRequest request){String parent=request.getHeader("traceparent");if(Collections.list(request.getHeaders("traceparent")).size()==1 && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") && !parent.substring(3,35).equals("0".repeat(32)) && !parent.substring(36,52).equals("0".repeat(16)))return parent;byte[] trace=new byte[16],span=new byte[8];do{random.nextBytes(trace);}while(Arrays.equals(trace,new byte[16]));do{random.nextBytes(span);}while(Arrays.equals(span,new byte[8]));return "00-"+HexFormat.of().formatHex(trace)+"-"+HexFormat.of().formatHex(span)+"-01";}
}
