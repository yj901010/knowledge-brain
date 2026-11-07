package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.Cookie;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class MetadataNameApiTest {
 NoteStore store;SpaceAuthorizationClient authority;MetadataDefinitionsController api;SimpleMeterRegistry metrics;
 UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),actor=ids.next(),parent=ids.next();
 @BeforeEach void prepare(){store=mock(NoteStore.class);authority=mock(SpaceAuthorizationClient.class);metrics=new SimpleMeterRegistry();api=new MetadataDefinitionsController(store,new NoteController(store,authority,metrics,"https://localhost:8443"),metrics);when(authority.authorize(eq(space),eq("a".repeat(43)),eq(false),anyString())).thenReturn(actor);}
 MockHttpServletRequest request(boolean tags){var r=new MockHttpServletRequest("GET","/api/spaces/"+space+(tags?"/tags":"/property-definitions"));r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));return r;}
 void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));}
 @Test void filteredCatalogPagesNormalizeQueryAndUseFreshAuthorityNoStoreAndBoundedMetrics(){
  UUID after=ids.next();var page=new NoteMetadataStore.Page(List.of(),null);when(store.definitions(space,true,null,20,"café")).thenReturn(page);when(store.definitions(space,false,after,20,"café")).thenReturn(page);
  var tags=request(true);tags.setParameter("q","cafe\u0301");assertThat(api.list(space,null,20,tags).getBody()).isEqualTo(page);var properties=request(false);properties.setParameter("q","café");assertThat(api.list(space,after,20,properties).getHeaders().getCacheControl()).isEqualTo("no-store");verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());assertThat(metrics.get("brain.content.metadata.reads").tag("type","tags_filtered").counter().count()).isEqualTo(1);assertThat(metrics.get("brain.content.metadata.reads").tag("type","property_definitions_filtered").counter().count()).isEqualTo(1);
  when(authority.authorize(eq(space),anyString(),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));assertThatThrownBy(()->api.list(space,after,20,properties)).isInstanceOf(ResponseStatusException.class);verify(store,times(1)).definitions(space,false,after,20,"café");
 }
 @Test void invalidCatalogInputsAndNameQueriesOnNoteRoutesNeverReachStoreOrAuthority(){
  for(String q:List.of("","x".repeat(65),"\u0000","\ud800","a\nb")){var r=request(true);r.setParameter("q",q);bad(()->api.list(space,null,20,r));}
  var duplicate=request(false);duplicate.setParameter("q",new String[]{"a","b"});bad(()->api.list(space,null,20,duplicate));
  var wrong=request(true);wrong.setParameter("q","study");bad(()->api.notes(space,parent,null,20,wrong));bad(()->api.propertyNotes(space,parent,null,20,wrong));verifyNoInteractions(store,authority);
 }
}
