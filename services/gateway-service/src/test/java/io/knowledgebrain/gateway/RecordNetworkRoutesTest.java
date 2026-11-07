package io.knowledgebrain.gateway;
import java.net.http.*;import java.util.*;import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class RecordNetworkRoutesTest {
 GatewayTransport transport;GatewayController api;String path="/api/spaces/01a10000-0000-7000-8000-000000000000/search/network";
 @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{transport=mock(GatewayTransport.class);api=new GatewayController(transport,new SimpleMeterRegistry(),"https://localhost:8443");HttpResponse<byte[]> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{}".getBytes());when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(a,b)->true));when(transport.send(any(),anyString(),anyMap(),any())).thenReturn(response);}
 MockHttpServletRequest request(String query){var r=new MockHttpServletRequest("GET",path);r.setSecure(true);r.setQueryString(query);return r;}
 @Test void allSixExplicitNetworkKindsReachBrainQuery()throws Exception{for(var kind:List.of("NOTE","CONCEPT","CLAIM","BOOKMARK","QUESTION","DECISION")){assertEquals("brain-query",GatewayRoute.select("GET",path).service());assertEquals(200,api.proxy(request("q=Saved&kind="+kind)).getStatusCode().value());}verify(transport,times(6)).send(any(),anyString(),anyMap(),any());}
 @Test void invalidDuplicateOrOtherSearchKindsStopAtGateway()throws Exception{for(var query:List.of("q=Saved&kind=SOURCE","q=Saved&kind=bookmark","q=Saved&kind=BOOKMARK&kind=QUESTION","q=Saved&kind=*","q=Saved&kind=BOOKMARK&index=private"))assertThrows(ResponseStatusException.class,()->api.proxy(request(query)));var note=request("q=Saved&kind=BOOKMARK");note.setRequestURI(path.replace("/network","/notes"));assertThrows(ResponseStatusException.class,()->api.proxy(note));verifyNoInteractions(transport);}
}
