package io.knowledgebrain.collaboration;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@ConditionalOnProperty(name="brain.collaboration.api.enabled",havingValue="true")
class GroupParticipationController {
 record Participation(GroupStore.Group group,String role){}
 record Page(List<Participation> items,UUID nextAfter){}
 private final GroupStore store;private final GroupSpaceClient space;
 GroupParticipationController(GroupStore store,GroupSpaceClient space){this.store=store;this.space=space;}
 private String credential(HttpServletRequest r)throws IOException{if(!r.isSecure())throw GroupInput.bad();if(r.getInputStream().read()!=-1)throw GroupInput.bad();var values=new ArrayList<String>();for(var header:Collections.list(r.getHeaders("Cookie")))for(var part:header.split(";")){var pair=part.trim().split("=",2);if(pair.length==2&&pair[0].equals("__Host-brain_guest"))values.add(pair[1]);}if(values.size()!=1||!values.getFirst().matches("[A-Za-z0-9_-]{43}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Browser session required");return values.getFirst();}
 private void query(HttpServletRequest r,Set<String> allowed){if(r.getQueryString()!=null&&r.getQueryString().length()>256)throw GroupInput.bad();for(var e:r.getParameterMap().entrySet())if(!allowed.contains(e.getKey())||e.getValue().length!=1)throw GroupInput.bad();}
 private String trace(HttpServletRequest r){var p=r.getHeader("traceparent");return p!=null&&p.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")&&!p.substring(3,35).equals("0".repeat(32))&&!p.substring(36,52).equals("0".repeat(16))?p:"00-"+UUID.randomUUID().toString().replace("-","")+"-"+UUID.randomUUID().toString().replace("-","").substring(0,16)+"-01";}
 private List<Participation> config(List<GroupSpaceClient.Grant> grants){var rows=store.active(grants.stream().map(GroupSpaceClient.Grant::groupId).toList());return rows.stream().flatMap(g->grants.stream().filter(a->a.groupId().equals(g.groupId())&&a.spaceId().equals(g.spaceId())).map(a->new Participation(g,a.role()))).toList();}
 @GetMapping("/api/groups/participating") ResponseEntity<Page> list(HttpServletRequest r)throws IOException{var token=credential(r);query(r,Set.of("after","limit"));int limit=20;var value=r.getParameter("limit");if(value!=null){if(!value.matches("[1-9][0-9]{0,2}")||Integer.parseInt(value)>100)throw GroupInput.bad();limit=Integer.parseInt(value);}var after=r.getParameter("after");var page=space.participations(token,after==null?null:GroupInput.id(after),limit,trace(r));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Page(config(page.grants()),page.nextAfter()));}
 @GetMapping("/api/groups/{groupId}/participation") ResponseEntity<Participation> detail(@PathVariable String groupId,HttpServletRequest r)throws IOException{var token=credential(r);query(r,Set.of());var id=GroupInput.id(groupId);var access=space.participation(token,List.of(id),trace(r));var rows=config(access.grants());if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Group unavailable");return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows.getFirst());}
}
