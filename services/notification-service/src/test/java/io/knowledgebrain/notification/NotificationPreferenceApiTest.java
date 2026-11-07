package io.knowledgebrain.notification;
import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;import org.springframework.web.server.ResponseStatusException;import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class NotificationPreferenceApiTest {
 NotificationStore store;SpaceAuthorizationClient auth;NotificationPreferenceController api;UUID space,actor;
 @BeforeEach void init(){space=DispatchFixtures.ids.next();actor=DispatchFixtures.ids.next();store=mock(NotificationStore.class);auth=mock(SpaceAuthorizationClient.class);when(auth.authorize(eq(space),any(),anyBoolean(),anyString())).thenReturn(actor);api=new NotificationPreferenceController(store,auth,"https://localhost:8443",new SimpleMeterRegistry());}
 MockHttpServletRequest r(String method,String body){var r=new MockHttpServletRequest(method,"/api/spaces/"+space+"/notification-preference");r.setSecure(true);r.setContentType("application/json");r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));r.addHeader("Origin","https://localhost:8443");r.addHeader("If-Match","\"np0\"");if(body!=null)r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));return r;}
 @Test void exactJsonStrongZeroVersionAndBoundsRejectAmbiguity()throws Exception{
  for(String body:List.of("{}","{\"inAppEnabled\":1}","{\"inAppEnabled\":true,\"inAppEnabled\":false}","{\"inAppEnabled\":false} {}","{\"inAppEnabled\":false,\"spaceId\":null}"))assertThrows(ResponseStatusException.class,()->api.put(space.toString(),r("PUT",body)));
  for(String tag:List.of("\"nt1\"","\"np01\"","W/\"np1\"","*","\"np9223372036854775808\"")){var q=r("PUT","{\"inAppEnabled\":false}");q.removeHeader("If-Match");q.addHeader("If-Match",tag);assertThrows(ResponseStatusException.class,()->api.put(space.toString(),q));}
  var missing=r("PUT","{\"inAppEnabled\":false}");missing.removeHeader("If-Match");assertEquals(428,assertThrows(ResponseStatusException.class,()->api.put(space.toString(),missing)).getStatusCode().value());
  assertEquals(413,assertThrows(ResponseStatusException.class,()->api.put(space.toString(),r("PUT"," ".repeat(1025)))).getStatusCode().value());verifyNoInteractions(store);
 }
 @Test void everyReadUsesFreshAuthorityNoCacheAndMutationUsesWriteAuthority()throws Exception{
  var v=new NotificationStore.Preference(null,space,"PRIVATE",0,true,null);when(store.preference(space)).thenReturn(v);
  for(int n=0;n<2;n++){var response=api.get(space.toString(),r("GET",null));assertEquals("no-store",response.getHeaders().getCacheControl());assertEquals("\"np0\"",response.getHeaders().getETag());}verify(auth,times(2)).authorize(eq(space),any(),eq(false),anyString());
  var off=new NotificationStore.Preference(DispatchFixtures.ids.next(),space,"PRIVATE",1,false,Instant.now().toString());when(store.preference(eq(space),eq(actor),eq(0L),eq(false),anyString())).thenReturn(off);assertEquals(off,api.put(space.toString(),r("PUT","{\"inAppEnabled\":false}")).getBody());verify(auth).authorize(eq(space),any(),eq(true),anyString());
  reset(store);when(auth.authorize(eq(space),any(),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));assertThrows(ResponseStatusException.class,()->api.suppressions(space.toString(),r("GET",null)));verifyNoInteractions(store);
 }
 @Test void badOriginCredentialsAndQueriesCannotReachCanonicalData(){
  var cross=r("PUT","{\"inAppEnabled\":false}");cross.removeHeader("Origin");assertEquals(403,assertThrows(ResponseStatusException.class,()->api.put(space.toString(),cross)).getStatusCode().value());
  var duplicate=r("GET",null);duplicate.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)),new Cookie("__Host-brain_guest","b".repeat(43)));assertEquals(401,assertThrows(ResponseStatusException.class,()->api.get(space.toString(),duplicate)).getStatusCode().value());
  var query=r("GET",null);query.addParameter("limit","1","2");assertThrows(ResponseStatusException.class,()->api.suppressions(space.toString(),query));assertThrows(ResponseStatusException.class,()->api.get(space.toString(),r("GET","{}")));verifyNoInteractions(store);
 }
}
