package io.knowledgebrain.gateway;
import java.net.http.*;import java.util.*;import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class PublicDiscoveryRoutesTest {
 GatewayTransport transport;GatewayController api;String path="/api/public/spaces/01a10000-0000-7000-8000-000000000000/search";
 @BeforeEach @SuppressWarnings("unchecked") void setup()throws Exception{transport=mock(GatewayTransport.class);api=new GatewayController(transport,new SimpleMeterRegistry(),"https://localhost:8443");HttpResponse<byte[]> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{}".getBytes());when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("application/json")),(a,b)->true));when(transport.send(any(),anyString(),anyMap(),any())).thenReturn(response);}
 MockHttpServletRequest request(String query){var r=new MockHttpServletRequest("GET",path);r.setSecure(true);r.setQueryString(query);return r;}
 @Test void allSixExplicitNetworkKindsReachBrainQuery()throws Exception{for(var kind:List.of("CONCEPT","ALIAS","CLAIM","SOURCE","EVIDENCE","MAPPING","VERIFICATION","QUALIFIER")){assertEquals("brain-query",GatewayRoute.select("GET",path).service());assertEquals(200,api.proxy(request("q=Saved&kind="+kind)).getStatusCode().value());}verify(transport,times(8)).send(any(),anyString(),anyMap(),any());}
 @Test void invalidOrDuplicatePublicQueriesNeverReachTransport()throws Exception{for(var query:List.of("q=Saved&kind=NOTE","q=Saved&kind=qualifier","q=Saved&kind=CLAIM&kind=SOURCE","q=Saved&index=private","q=Saved&offset=-1","q=Saved&limit=51"))assertThrows(ResponseStatusException.class,()->api.proxy(request(query)));var graph=request("depth=4");graph.setRequestURI(path.replace("/search","/graph"));assertThrows(ResponseStatusException.class,()->api.proxy(graph));verifyNoInteractions(transport);}
 @Test void publicDiscoveryNeverExposesOwnerFeedsOrWrites(){for(var method:List.of("POST","PUT","DELETE"))assertNull(GatewayRoute.select(method,path));assertNull(GatewayRoute.select("POST","/internal/knowledge/public-check"));assertNull(GatewayRoute.select("GET","/internal/knowledge/public-snapshots"));}
}
