package io.knowledgebrain.brainquery;
import java.util.*;
import java.security.SecureRandom;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.micrometer.core.instrument.MeterRegistry;
@RestController
@ConditionalOnProperty(name="brain.query.graph.enabled",havingValue="true")
class NoteGraphController {
 private final SpaceAuthorizationClient authority;private final GraphReadClient reader;private final MeterRegistry metrics;private final SecureRandom random=new SecureRandom();
 NoteGraphController(SpaceAuthorizationClient authority,GraphReadClient reader,MeterRegistry metrics){this.authority=authority;this.reader=reader;this.metrics=metrics;}
 @GetMapping("/api/spaces/{spaceId}/graph/notes") ResponseEntity<NoteGraphStore.View> graph(@PathVariable UUID spaceId,HttpServletRequest request){
 var timing=io.micrometer.core.instrument.Timer.start(metrics);
 try{
  if(spaceId.version()!=7 || spaceId.variant()!=2 || !request.isSecure())throw bad();
  for(var entry:request.getParameterMap().entrySet())if(!"limit".equals(entry.getKey()) || entry.getValue().length!=1)throw bad();
  String value=request.getParameter("limit");int limit=100;
  if(value!=null){if(!value.matches("[1-9][0-9]{0,2}"))throw bad();limit=Integer.parseInt(value);if(limit>200)throw bad();}
  String token=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if("__Host-brain_guest".equals(cookie.getName())){if(token!=null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Guest credential required");token=cookie.getValue();}
  if(token==null || !token.matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Guest credential required");
  String trace=traceparent(request);authority.authorize(spaceId,token,trace,"NOTE_GRAPH");
  var result=reader.read(spaceId,limit);metrics.counter("brain.graph.requests","outcome","success").increment();
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("X-Trace-Id",trace.substring(3,35)).body(result);
 }catch(ResponseStatusException e){metrics.counter("brain.graph.requests","outcome","denied_or_unavailable").increment();throw e;}
 catch(GraphReadClient.Unavailable e){metrics.counter("brain.graph.requests","outcome","storage_unavailable").increment();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Graph unavailable");}
 finally{timing.stop(metrics.timer("brain.graph.request.duration"));}
 }
 private static ResponseStatusException bad(){return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid graph request");}
 private String traceparent(HttpServletRequest request){String parent=request.getHeader("traceparent");if(Collections.list(request.getHeaders("traceparent")).size()==1 && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") && !parent.substring(3,35).equals("0".repeat(32)) && !parent.substring(36,52).equals("0".repeat(16)))return parent;byte[] trace=new byte[16],span=new byte[8];do{random.nextBytes(trace);}while(Arrays.equals(trace,new byte[16]));do{random.nextBytes(span);}while(Arrays.equals(span,new byte[8]));return "00-"+HexFormat.of().formatHex(trace)+"-"+HexFormat.of().formatHex(span)+"-01";}
}
