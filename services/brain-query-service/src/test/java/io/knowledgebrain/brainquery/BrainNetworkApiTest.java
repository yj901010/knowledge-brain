package io.knowledgebrain.brainquery;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.Cookie;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class BrainNetworkApiTest {
 UUID space=UUID.fromString("01900000-0000-7000-8000-000000000001");BrainProjectionConsumer consumer;NoteSearchClient reader;SpaceAuthorizationClient authority;BrainNetworkController api;
 @BeforeEach void setup(){consumer=mock(BrainProjectionConsumer.class);reader=mock(NoteSearchClient.class);authority=mock(SpaceAuthorizationClient.class);api=new BrainNetworkController(consumer,reader,authority,new SimpleMeterRegistry());when(consumer.isReady()).thenReturn(true);}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));return r;}
 @Test void everyRequestUsesFreshScopedDecisionBeforeRead(){var r=request();when(consumer.read(space,100)).thenReturn(new BrainGraphStore.View(List.of(),List.of(),false));api.graph(space.toString(),r);api.graph(space.toString(),r);var order=inOrder(authority,consumer);for(int i=0;i<2;i++){order.verify(authority).authorize(eq(space),eq("a".repeat(43)),anyString(),eq("BRAIN_GRAPH"));order.verify(consumer).read(space,100);}r.addParameter("q","query");when(reader.searchBrain(space,"query",0,20,null)).thenReturn(JsonMapper.builder().build().readTree("{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}"));assertThat(api.search(space.toString(),r).getHeaders().getCacheControl()).isEqualTo("no-store");verify(authority).authorize(eq(space),anyString(),anyString(),eq("BRAIN_SEARCH"));}
 @Test void denialNeverReadsCountsOrTitles(){doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(authority).authorize(eq(space),anyString(),anyString(),eq("BRAIN_GRAPH"));assertThatThrownBy(()->api.graph(space.toString(),request())).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(reader,consumer);}
 @Test void malformedRequestsStopBeforeAuthority(){for(String value:List.of("0","201","01","999999")){var r=request();r.addParameter("limit",value);assertThatThrownBy(()->api.graph(space.toString(),r)).isInstanceOf(ResponseStatusException.class);}var duplicate=request();duplicate.addParameter("limit","1","2");assertThatThrownBy(()->api.graph(space.toString(),duplicate)).isInstanceOf(ResponseStatusException.class);var body=request();body.setContent(new byte[]{1});assertThatThrownBy(()->api.graph(space.toString(),body)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(authority,consumer,reader);}
 @Test void readinessLossAndMalformedHitsFailAs503(){var r=request();r.addParameter("q","query");when(consumer.isReady()).thenReturn(false);assertThatThrownBy(()->api.search(space.toString(),r)).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));verifyNoInteractions(reader);var malformed=JsonMapper.builder().build().readTree("{\"total\":{\"value\":1,\"relation\":\"eq\"},\"hits\":[{\"_source\":{\"entityId\":\"bad-id\"}}]}");assertThatThrownBy(()->BrainNetworkController.page(malformed,space,0,20,null)).isInstanceOf(NoteSearchClient.Unavailable.class);}
}
