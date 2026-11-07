package io.knowledgebrain.learning;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import jakarta.servlet.http.Cookie;import org.springframework.mock.web.MockHttpServletRequest;import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
class RoadmapAdoptionApiTest {
 RoadmapAdoptionStore store;SpaceAuthorizationClient authority;RoadmapController browser;PublicRoadmapController pub;RoadmapAdoptionController api;UUID space,source,id,actor;
 @BeforeEach void setup(){var ids=new UuidV7(Clock.systemUTC());space=ids.next();source=ids.next();id=ids.next();actor=ids.next();store=mock(RoadmapAdoptionStore.class);authority=mock(SpaceAuthorizationClient.class);var metrics=new SimpleMeterRegistry();browser=new RoadmapController(mock(RoadmapStore.class),authority,metrics,"https://localhost:8443");pub=new PublicRoadmapController(mock(PublicRoadmapStore.class),authority,browser,metrics,"https://localhost:8443");api=new RoadmapAdoptionController(store,browser,pub,metrics);when(authority.authorize(eq(space),any(),anyBoolean(),anyString())).thenReturn(actor);when(authority.authorize(eq(source),any(),eq("PUBLIC_ROADMAP_READ"),eq("PUBLIC"),anyString())).thenReturn(actor);}
 MockHttpServletRequest request(String method,String body){var r=new MockHttpServletRequest(method,"/");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));r.addHeader("Origin","https://localhost:8443");r.setContentType("application/json");if(body!=null)r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
 String input(){return "{\"sourceSpaceId\":\""+source+"\",\"sourceRoadmapId\":\""+id+"\",\"sourceVersion\":1}";}
 @Test void bothBoundariesAreFreshAndForeignOrChangedPrincipalNeverWrites()throws Exception{
  when(authority.authorize(eq(source),any(),anyString(),anyString(),anyString())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
  assertEquals(404,assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),request("POST",input()))).getStatusCode().value());verifyNoInteractions(store);
  when(authority.authorize(eq(source),any(),anyString(),anyString(),anyString())).thenReturn(source);
  assertEquals(409,assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),request("POST",input()))).getStatusCode().value());verifyNoInteractions(store);
  when(authority.authorize(eq(space),any(),eq(true),anyString())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));clearInvocations(authority);
  assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),request("POST",input())));verify(authority,never()).authorize(eq(source),any(),anyString(),anyString(),anyString());
 }
 @Test void strictBodyQueryAndSameOriginBeforeMutation()throws Exception{
  for(String body:List.of("{}",input()+" {}",input().replace(":1}",":1.5}"),input().replace(":1}",":0}"),input().replace(":1}",":1,\"sourceVersion\":2}"),input().replace(":1}",":1,\"actorId\":\"spoof\"}"))){assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),request("POST",body)));}
  var r=request("POST",input());r.addParameter("extra","1");assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),r));
  var cross=request("POST",input());cross.removeHeader("Origin");assertEquals(403,assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),cross)).getStatusCode().value());
  var insecure=request("POST",input());insecure.setSecure(false);assertThrows(ResponseStatusException.class,()->api.adopt(space.toString(),insecure));verifyNoInteractions(store);
 }
 @Test void retryReturnsSameLocationAndOwnSourceUsesPersonalAuthority()throws Exception{
  var a=new RoadmapAdoptionStore.Adoption(actor,space,actor,source,id,1,"Source",actor,Instant.now().toString(),List.of(),List.of());
  when(store.adopt(eq(space),eq(source),eq(id),eq(1L),eq(actor),anyString())).thenReturn(new RoadmapAdoptionStore.Result(a,true),new RoadmapAdoptionStore.Result(a,false));
  var first=api.adopt(space.toString(),request("POST",input()));assertEquals(201,first.getStatusCode().value());var retry=api.adopt(space.toString(),request("POST",input()));assertEquals(200,retry.getStatusCode().value());assertEquals(first.getHeaders().getLocation(),retry.getHeaders().getLocation());assertEquals("no-store",retry.getHeaders().getCacheControl());
  when(store.source(space,actor)).thenReturn(new RoadmapAdoptionStore.Source(a));assertEquals(new RoadmapAdoptionStore.Source(a),api.source(space.toString(),actor.toString(),request("GET",null)).getBody());verify(authority).authorize(eq(space),any(),eq(false),anyString());
 }
}
