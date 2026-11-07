package io.knowledgebrain.learning;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import jakarta.servlet.http.Cookie;import org.springframework.mock.web.MockHttpServletRequest;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
class GroupRoadmapApiTest {
 GroupRoadmapController api;GroupRoadmapStore store;SpaceAuthorizationClient auth;UUID space,source,id,actor;
 @BeforeEach void setup(){var ids=new UuidV7(Clock.systemUTC());space=ids.next();source=ids.next();id=ids.next();actor=ids.next();store=mock(GroupRoadmapStore.class);auth=mock(SpaceAuthorizationClient.class);var metrics=new SimpleMeterRegistry();var browser=new RoadmapController(mock(RoadmapStore.class),auth,metrics,"https://localhost:8443");var pub=new PublicRoadmapController(mock(PublicRoadmapStore.class),auth,browser,metrics,"https://localhost:8443");api=new GroupRoadmapController(store,browser,pub,auth,metrics,"https://localhost:8443");when(auth.authorize(any(),any(),anyString(),anyString(),anyString())).thenReturn(actor);}
 MockHttpServletRequest req(String method,String body){var r=new MockHttpServletRequest(method,"/");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));r.addHeader("Origin","https://localhost:8443");r.setContentType("application/json");if(body!=null)r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
 String input(){return "{\"sourceSpaceId\":\""+source+"\",\"sourceRoadmapId\":\""+id+"\",\"sourceVersion\":1}";}
 @Test void strictInputAndOriginNeverReachStore()throws Exception{
  for(String body:List.of("{}",input()+" {}",input().replace(":1}",":0}"),input().replace(":1}",":1.5}"),input().replace(":1}",":1,\"sourceVersion\":2}"),input().replace(":1}",":1,\"actorId\":\"spoof\"}")))assertThrows(ResponseStatusException.class,()->api.attach(space.toString(),req("POST",body)));
  var cross=req("POST",input());cross.removeHeader("Origin");assertEquals(403,assertThrows(ResponseStatusException.class,()->api.attach(space.toString(),cross)).getStatusCode().value());
  var query=req("GET",null);query.addParameter("token","bad");assertThrows(ResponseStatusException.class,()->api.list(space.toString(),query));verifyNoInteractions(store);
 }
 @Test void freshGroupAndSourceChecksFailClosed()throws Exception{
  when(auth.authorize(eq(space),any(),eq("GROUP_ROADMAP_WRITE"),eq("PRIVATE"),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
  assertThrows(ResponseStatusException.class,()->api.attach(space.toString(),req("POST",input())));verify(auth,never()).authorize(eq(source),any(),anyString(),anyString(),anyString());
  when(auth.authorize(eq(space),any(),eq("GROUP_ROADMAP_WRITE"),eq("PRIVATE"),anyString())).thenReturn(actor);when(auth.authorize(eq(source),any(),eq("PUBLIC_ROADMAP_READ"),eq("PUBLIC"),anyString())).thenReturn(id);
  assertEquals(409,assertThrows(ResponseStatusException.class,()->api.attach(space.toString(),req("POST",input()))).getStatusCode().value());verifyNoInteractions(store);
 }
 @Test void retirementRequiresStrongVersionAndEmptyBody(){
  var r=req("DELETE",null);assertEquals(428,assertThrows(ResponseStatusException.class,()->api.retire(space.toString(),id.toString(),r)).getStatusCode().value());
  for(String tag:List.of("*","W/\"gr1\"","\"rm1\"","\"gr0\"","\"gr9999999999999999999\"")){var x=req("DELETE",null);x.addHeader("If-Match",tag);assertThrows(ResponseStatusException.class,()->api.retire(space.toString(),id.toString(),x));}
  var x=req("DELETE","{}");x.addHeader("If-Match","\"gr1\"");assertThrows(ResponseStatusException.class,()->api.retire(space.toString(),id.toString(),x));verifyNoInteractions(store);
 }
 @Test void listAndHistoryUseDedicatedReadCapability(){
  when(store.list(space,null,20)).thenReturn(new GroupRoadmapStore.Page(List.of(),null));assertEquals(200,api.list(space.toString(),req("GET",null)).getStatusCode().value());
  api.history(space.toString(),id.toString(),req("GET",null));verify(auth,times(2)).authorize(eq(space),any(),eq("GROUP_ROADMAP_READ"),eq("PRIVATE"),anyString());verify(auth,never()).authorize(any(),any(),anyBoolean(),anyString());
 }
}
