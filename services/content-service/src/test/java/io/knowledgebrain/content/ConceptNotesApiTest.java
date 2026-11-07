package io.knowledgebrain.content;

import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptNotesApiTest {
    NoteStore store;SpaceAuthorizationClient authority;ConceptNotesController api;
    SimpleMeterRegistry metrics;UUID space,actor,concept,note;
    @BeforeEach void setup(){
        store=mock(NoteStore.class);authority=mock(SpaceAuthorizationClient.class);metrics=new SimpleMeterRegistry();
        api=new ConceptNotesController(store,new NoteController(store,authority,metrics,"https://localhost:8443"),metrics);
        var ids=new UuidV7(Clock.systemUTC());space=ids.next();actor=ids.next();concept=ids.next();note=ids.next();
        when(authority.authorize(eq(space),eq("a".repeat(43)),eq(false),anyString())).thenReturn(actor);
    }
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));return r;}
    @Test void eachReadRequiresFreshNotePermissionAndReturnsOnlyBoundedNoStoreSummary(){
        var page=new NoteConceptStore.NotesPage(List.of(new NoteConceptStore.LinkedNote(note,"Title")),null);
        when(store.notesByConcept(space,concept,null,20)).thenReturn(page);
        var response=api.get(space,concept,request());assertThat(response.getBody()).isEqualTo(page);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var spoof=request();spoof.addHeader("X-Principal-Id",actor.toString());spoof.addHeader("Authorization","Bearer irrelevant");api.get(space,concept,spoof);
        verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());
        verify(store,times(2)).notesByConcept(space,concept,null,20);verifyNoMoreInteractions(store);
        assertThat(metrics.get("brain.content.concept.reads").tag("type","linked_notes").counter().count()).isEqualTo(2);
    }
    @Test void deniedAndUnavailableAuthorityNeverReadOrMutateContent(){
        for(var status:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){
            doThrow(new ResponseStatusException(status,"Note access unavailable")).when(authority).authorize(eq(space),anyString(),eq(false),anyString());
            assertThatThrownBy(()->api.get(space,concept,request())).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode()).isEqualTo(status));
            assertThat(new ApiErrors().status(new ResponseStatusException(status)).getHeaders().getCacheControl()).isEqualTo("no-store");
        }
        verifyNoInteractions(store);
    }
    @Test void malformedDuplicateUnknownAndNonCanonicalParametersNeverReachAuthorityOrStore(){
        for(String limit:List.of("0","101","1000","01","-1","+1","","1.0")){
            var r=request();r.setParameter("limit",limit);assertThatThrownBy(()->api.get(space,concept,r)).isInstanceOf(ResponseStatusException.class);
        }
        for(String cursor:List.of("",UUID.randomUUID().toString(),note.toString().toUpperCase(Locale.ROOT),"7-1-7-8-1")){
            var r=request();r.setParameter("after",cursor);assertThatThrownBy(()->api.get(space,concept,r)).isInstanceOf(ResponseStatusException.class);
        }
        var unknown=request();unknown.setParameter("q","secret");assertThatThrownBy(()->api.get(space,concept,unknown)).isInstanceOf(ResponseStatusException.class);
        for(String key:List.of("limit","after")){
            var r=request();String value=key.equals("limit")?"20":note.toString();r.setParameter(key,new String[]{value,value});assertThatThrownBy(()->api.get(space,concept,r)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(()->api.get(space,UUID.randomUUID(),request())).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(store,authority);
    }
    @Test void validExclusiveCursorAndMaximumAreForwardedWithoutDefinitionLookup(){
        var r=request();r.setParameter("limit","100");r.setParameter("after",note.toString());
        when(store.notesByConcept(space,concept,note,100)).thenReturn(new NoteConceptStore.NotesPage(List.of(),null));
        assertThat(api.get(space,concept,r).getBody().items()).isEmpty();verify(store).notesByConcept(space,concept,note,100);verifyNoMoreInteractions(store);
    }
    @Test void insecureMissingAndDuplicateCredentialsCannotReadContent(){
        var insecure=request();insecure.setSecure(false);assertThatThrownBy(()->api.get(space,concept,insecure)).isInstanceOf(ResponseStatusException.class);
        var duplicate=request();duplicate.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)),new Cookie("__Host-brain_guest","b".repeat(43)));assertThatThrownBy(()->api.get(space,concept,duplicate)).isInstanceOf(ResponseStatusException.class);
        when(authority.authorize(eq(space),isNull(),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var missing=request();missing.setCookies();assertThatThrownBy(()->api.get(space,concept,missing)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(store);
    }
    @Test void persistenceFailureHasGenericNoStoreErrorAndNeverRetriesTheQuery(){
        when(store.notesByConcept(space,concept,null,20)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Private database failure"));
        assertThatThrownBy(()->api.get(space,concept,request())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        var error=new ApiErrors().persistence();assertThat(error.getStatusCode().value()).isEqualTo(503);assertThat(error.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(error.getBody().error()).doesNotContain("Private");
        verify(store).notesByConcept(space,concept,null,20);verifyNoMoreInteractions(store);
    }
}
