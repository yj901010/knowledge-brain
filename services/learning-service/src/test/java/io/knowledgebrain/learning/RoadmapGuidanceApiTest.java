package io.knowledgebrain.learning;

import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RoadmapGuidanceApiTest {
 RoadmapGuidanceStore store;SpaceAuthorizationClient authority;RoadmapGuidanceController api;SimpleMeterRegistry metrics;
 UUID space=RoadmapGuidanceRulesTest.id(901),roadmap=RoadmapGuidanceRulesTest.id(900),guest=RoadmapGuidanceRulesTest.id(902),registered=RoadmapGuidanceRulesTest.id(903);
 String path="/api/spaces/"+space+"/roadmaps/"+roadmap+"/learning-guidance";
 @BeforeEach void setup(){store=mock(RoadmapGuidanceStore.class);authority=mock(SpaceAuthorizationClient.class);metrics=new SimpleMeterRegistry();api=new RoadmapGuidanceController(store,new RoadmapController(mock(RoadmapStore.class),authority,metrics,"https://localhost:8443"),metrics);when(authority.authorize(eq(space),eq("a".repeat(43)),eq(false),anyString())).thenReturn(guest);when(authority.authorize(eq(space),eq("b".repeat(43)),eq(false),anyString())).thenReturn(registered);}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET",path);r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));return r;}
 @Test void eachGuestAndRegisteredReadUsesFreshPrivateReadAuthorityBeforeStore()throws Exception{
  var result=RoadmapGuidanceRules.compute(RoadmapGuidanceRulesTest.graph(0,List.of()),List.of());when(store.read(space,roadmap)).thenReturn(result);
  var guestRequest=request();var registeredRequest=request();registeredRequest.setCookies(new Cookie("__Host-brain_guest","b".repeat(43)));
  assertEquals("no-store",api.guidance(space.toString(),roadmap.toString(),guestRequest).getHeaders().getCacheControl());assertEquals(result,api.guidance(space.toString(),roadmap.toString(),registeredRequest).getBody());
  var order=inOrder(authority,store);order.verify(authority).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());order.verify(store).read(space,roadmap);order.verify(authority).authorize(eq(space),eq("b".repeat(43)),eq(false),anyString());order.verify(store).read(space,roadmap);
  assertEquals(2,metrics.get("brain.learning.records").tag("operation","guidance-read").counter().count());assertEquals(1,metrics.getMeters().size());assertEquals(List.of("operation"),metrics.getMeters().getFirst().getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).toList());
 }
 @Test void noQueryActorOverrideDuplicateParameterBodyOrTransferEncodingReachesAuthority()throws Exception{
  for(String key:List.of("actorId","principalId","spaceId","algorithm","limit")){var r=request();r.addParameter(key,"spoof");assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),r));}
  var duplicate=request();duplicate.addParameter("actorId","a","b");assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),duplicate));
  var body=request();body.setContent("{}".getBytes());assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),body));
  var transfer=request();transfer.addHeader("Transfer-Encoding","chunked");assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),transfer));
  var noLength=new MockHttpServletRequest("GET",path){@Override public long getContentLengthLong(){return -1;}};noLength.setContent("{}".getBytes());noLength.setSecure(true);assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),noLength));
  verifyNoInteractions(authority,store);assertTrue(metrics.getMeters().isEmpty());
 }
 @Test void malformedIdsInsecureAndDuplicateCredentialRequestsNeverReadCanonicalRows()throws Exception{
  assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),"01900000-0000-7000-8000-000000000ABC",request()));
  assertThrows(ResponseStatusException.class,()->api.guidance("00000000-0000-4000-8000-000000000001",roadmap.toString(),request()));
  var insecure=request();insecure.setSecure(false);assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),insecure));
  var duplicate=request();duplicate.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)),new Cookie("__Host-brain_guest","b".repeat(43)));assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),duplicate));verifyNoInteractions(authority,store);
 }
 @Test void currentRevocationAndAuthorityFailureNeverReadStore()throws Exception{
  for(HttpStatus status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){when(authority.authorize(eq(space),any(),eq(false),anyString())).thenThrow(new ResponseStatusException(status));assertEquals(status,assertThrows(ResponseStatusException.class,()->api.guidance(space.toString(),roadmap.toString(),request())).getStatusCode());}verifyNoInteractions(store);assertTrue(metrics.getMeters().isEmpty());
 }
 @Test void missingAndCorruptCanonicalFailuresRemainPrivateNoStoreResponses()throws Exception{
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new RoadmapErrors()).build();
  doThrow(RoadmapStore.missing()).when(store).read(space,roadmap);mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).secure(true).cookie(new Cookie("__Host-brain_guest","a".repeat(43)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store"));
  doThrow(new IllegalStateException("private raw canonical content")).when(store).read(space,roadmap);mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).secure(true).cookie(new Cookie("__Host-brain_guest","a".repeat(43)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{\"error\":\"Roadmap unavailable\"}"));assertTrue(metrics.getMeters().isEmpty());
 }
}
