package io.knowledgebrain.gateway;
import java.net.http.*;import java.util.*;import org.junit.jupiter.api.*;import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class DecisionProxyTest {
 GatewayTransport transport;GatewayController proxy;String root="/api/spaces/01a10000-0000-7000-8000-000000000000/decisions";
 @BeforeEach @SuppressWarnings("unchecked") void init()throws Exception{transport=mock(GatewayTransport.class);proxy=new GatewayController(transport,new SimpleMeterRegistry(),"https://localhost:8443");HttpResponse<byte[]> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{}".getBytes());when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(a,b)->true));when(transport.send(any(),anyString(),anyMap(),any())).thenReturn(response);}
 MockHttpServletRequest request(String method,String path){var r=new MockHttpServletRequest(method,path);r.setSecure(true);r.addHeader("Origin","https://localhost:8443");r.setContentType("application/json");return r;}
 @Test void validCreationKeyIsRequiredAndForwardedOnlyForDecisionCreation()throws Exception{
  assertEquals(400,assertThrows(ResponseStatusException.class,()->proxy.proxy(request("POST",root))).getStatusCode().value());
  var duplicate=request("POST",root);duplicate.addHeader("Idempotency-Key","a".repeat(32));duplicate.addHeader("Idempotency-Key","b".repeat(32));assertThrows(ResponseStatusException.class,()->proxy.proxy(duplicate));verifyNoInteractions(transport);
  var valid=request("POST",root);valid.addHeader("Idempotency-Key","a".repeat(32));proxy.proxy(valid);verify(transport).send(eq(new GatewayRoute("content",root)),eq("POST"),argThat(h->h.get("Idempotency-Key").equals(List.of("a".repeat(32)))),any());
  String note=root.replace("/decisions","/notes");var other=request("POST",note);other.addHeader("Idempotency-Key","a".repeat(32));proxy.proxy(other);verify(transport).send(eq(new GatewayRoute("content",note)),eq("POST"),argThat(h->!h.containsKey("Idempotency-Key")),any());
 }
 @Test void readAndArchiveBodiesAndAmbiguousOrMutationQueriesNeverReachContent()throws Exception{
  for(String method:List.of("GET","DELETE")){var r=request(method,root+"/01a10000-0000-7000-8000-000000000001");r.setContent("{}".getBytes());assertThrows(ResponseStatusException.class,()->proxy.proxy(r));}
  for(String query:List.of("state=ACTIVE&state=RETIRED","unknown=x","after="+"a".repeat(257))){var r=request("GET",root);r.setQueryString(query);assertThrows(ResponseStatusException.class,()->proxy.proxy(r));}
  var post=request("POST",root);post.setQueryString("state=ACTIVE");assertThrows(ResponseStatusException.class,()->proxy.proxy(post));verifyNoInteractions(transport);
 }
}
