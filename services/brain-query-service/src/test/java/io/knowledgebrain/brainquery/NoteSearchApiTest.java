package io.knowledgebrain.brainquery;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class NoteSearchApiTest {
 UUID space=UUID.fromString("01900000-0000-7000-8000-000000000001"),note=UUID.fromString("01900000-0000-7000-8000-000000000002");SpaceAuthorizationClient authority;NoteSearchClient reader;NoteSearchController api;
 @BeforeEach void setup(){authority=mock(SpaceAuthorizationClient.class);reader=mock(NoteSearchClient.class);api=new NoteSearchController(authority,reader,new SimpleMeterRegistry());}
 MockHttpServletRequest request(){var req=new MockHttpServletRequest();req.setSecure(true);req.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));req.addParameter("q","sample");return req;}
 @Test void freshAuthorityAlwaysPrecedesSearchAndResponseContainsOnlyPublicDtoFields() {
  var hits=JsonMapper.builder().build().readTree("{\"total\":{\"value\":1,\"relation\":\"eq\"},\"hits\":[{\"_source\":{\"noteId\":\""+note+"\",\"title\":\"Title\",\"markdown\":\"not returned\"}}]}".replace("\\\"","\""));
  when(reader.search(space,"sample",0,20)).thenReturn(hits);var req=request();req.addHeader("X-Principal-Id",note.toString());req.addHeader("Authorization","Bearer spoof");
  var first=api.search(space,req);api.search(space,req);var order=inOrder(authority,reader);order.verify(authority).authorize(eq(space),eq("a".repeat(43)),anyString());order.verify(reader).search(space,"sample",0,20);order.verify(authority).authorize(eq(space),eq("a".repeat(43)),anyString());order.verify(reader).search(space,"sample",0,20);
  assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(first.getBody().items()).containsExactly(new NoteSearchController.Item(note,"Title"));assertThat(first.getBody().total()).isEqualTo(1);
 }
 @Test void denialOrAuthorityOutageNeverQueriesHitsOrCounts() {
  for(var code:new HttpStatus[]{HttpStatus.UNAUTHORIZED,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE}){when(authority.authorize(eq(space),anyString(),anyString())).thenThrow(new ResponseStatusException(code,"Unavailable"));assertThatThrownBy(()->api.search(space,request())).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode()).isEqualTo(code));}verifyNoInteractions(reader);
 }
 @Test void malformedQueriesAndMissingDuplicateOrSpoofedCredentialsFailBeforeAuthority() {
  for(String query:new String[]{"","a".repeat(201),"line\ncontrol"}){var req=request();req.setParameter("q",query);assertThatThrownBy(()->api.search(space,req)).isInstanceOf(ResponseStatusException.class);}
  var duplicate=request();duplicate.addParameter("q","other");assertThatThrownBy(()->api.search(space,duplicate)).isInstanceOf(ResponseStatusException.class);
  var extra=request();extra.addParameter("index","anything");assertThatThrownBy(()->api.search(space,extra)).isInstanceOf(ResponseStatusException.class);
  var missing=request();missing.setCookies();missing.addHeader("Authorization","Bearer "+"a".repeat(43));assertThatThrownBy(()->api.search(space,missing)).isInstanceOf(ResponseStatusException.class);
  var cookie=request();cookie.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)),new Cookie("__Host-brain_guest","b".repeat(43)));assertThatThrownBy(()->api.search(space,cookie)).isInstanceOf(ResponseStatusException.class);
  var http=request();http.setSecure(false);http.addHeader("X-Forwarded-Proto","https");assertThatThrownBy(()->api.search(space,http)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(reader,authority);
 }
 @Test void storageFailureReturnsGeneric503AndInvalidDecisionFailsClosed() {
  when(reader.search(space,"sample",0,20)).thenThrow(new NoteSearchClient.Unavailable());assertThatThrownBy(()->api.search(space,request())).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));
  for(var decision:List.of(new SpaceAuthorizationClient.Decision(note,note,"NOTE_SEARCH","PRIVATE"),new SpaceAuthorizationClient.Decision(space,note,"NOTE_WRITE","PRIVATE"),new SpaceAuthorizationClient.Decision(space,note,"NOTE_SEARCH","PUBLIC")))assertThatThrownBy(()->SpaceAuthorizationClient.validate(decision,space)).isInstanceOf(ResponseStatusException.class);
 }
 @Test void incompleteTotalsMalformedSourcesAndPaginationBoundsAreRejected() {
  var json=JsonMapper.builder().build();assertThatThrownBy(()->NoteSearchController.page(json.readTree("{}"),0,20)).isInstanceOf(NoteSearchClient.Unavailable.class);
  assertThatThrownBy(()->NoteSearchController.page(json.readTree("{\"total\":{\"value\":1,\"relation\":\"gte\"},\"hits\":[]}"),0,20)).isInstanceOf(NoteSearchClient.Unavailable.class);
  for(String value:new String[]{"-1","1001","999999999","01"}){var req=request();req.addParameter("offset",value);assertThatThrownBy(()->api.search(space,req)).isInstanceOf(ResponseStatusException.class);}verifyNoInteractions(authority,reader);
 }
}
