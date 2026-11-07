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
class TagNotesApiTest {
    NoteStore store;SpaceAuthorizationClient authority;MetadataDefinitionsController api;SimpleMeterRegistry metrics;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),tag=ids.next(),actor=ids.next();
    @BeforeEach void prepare(){store=mock(NoteStore.class);authority=mock(SpaceAuthorizationClient.class);metrics=new SimpleMeterRegistry();api=new MetadataDefinitionsController(store,new NoteController(store,authority,metrics,"https://localhost:8443"),metrics);when(authority.authorize(eq(space),eq("a".repeat(43)),eq(false),anyString())).thenReturn(actor);}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest("GET","/api/spaces/"+space+"/tags/"+tag+"/notes");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));return r;}
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code));}
    @Test void eachPageUsesFreshSpaceAuthorityAndNoStoreWithBoundedMetrics(){
        UUID after=ids.next();var page=new NoteStore.Page(List.of(),null);when(store.tagNotes(space,tag,null,20)).thenReturn(page);when(store.tagNotes(space,tag,after,20)).thenReturn(page);
        var r=request();r.addHeader("X-Principal-Id",ids.next().toString());assertThat(api.notes(space,tag,null,20,r).getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(api.notes(space,tag,after,20,r).getBody()).isEqualTo(page);
        verify(authority,times(2)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());verify(store).tagNotes(space,tag,null,20);verify(store).tagNotes(space,tag,after,20);assertThat(metrics.get("brain.content.metadata.reads").tag("type","tag_notes").counter().count()).isEqualTo(2);
    }
    @Test void invalidScopeCursorsLimitsAndUnknownDuplicateEmptyQueriesFailBeforeStore(){
        for(int limit:new int[]{0,-1,101})status(400,()->api.notes(space,tag,null,limit,request()));
        status(400,()->api.notes(space,UUID.randomUUID(),null,20,request()));status(400,()->api.notes(space,tag,UUID.randomUUID(),20,request()));status(400,()->api.notes(UUID.randomUUID(),tag,null,20,request()));
        for(String key:List.of("state","spaceId","before","search")){var r=request();r.setParameter(key,"spoof");status(400,()->api.notes(space,tag,null,20,r));status(400,()->api.list(space,null,20,r));}
        var duplicate=request();duplicate.setParameter("limit",new String[]{"20","1"});status(400,()->api.notes(space,tag,null,20,duplicate));status(400,()->api.list(space,null,20,duplicate));
        var empty=request();empty.setParameter("after","");status(400,()->api.notes(space,tag,null,20,empty));verifyNoInteractions(store,authority);
    }
    @Test void denialOutageAndInsecureReadsNeverReachStore(){
        var insecure=request();insecure.setSecure(false);status(400,()->api.notes(space,tag,null,20,insecure));
        for(var code:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){when(authority.authorize(eq(space),anyString(),eq(false),anyString())).thenThrow(new ResponseStatusException(code));status(code.value(),()->api.notes(space,tag,null,20,request()));}
        verifyNoInteractions(store);assertThat(metrics.find("brain.content.metadata.reads").counter()).isNull();
    }
}
