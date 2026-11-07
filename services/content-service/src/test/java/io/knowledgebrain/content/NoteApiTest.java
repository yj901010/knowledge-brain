package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class NoteApiTest {
    NoteStore store;SpaceAuthorizationClient authority;NoteController api;UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,note;
    @BeforeEach void prepare() { store=mock(NoteStore.class);authority=mock(SpaceAuthorizationClient.class);api=new NoteController(store,authority,new SimpleMeterRegistry(),"https://localhost:8443");space=ids.next();actor=ids.next();note=ids.next();when(authority.authorize(eq(space),eq("a".repeat(43)),anyBoolean(),anyString())).thenReturn(actor); }
    MockHttpServletRequest request(String method) { var r=new MockHttpServletRequest(method,"/api/spaces/"+space+"/notes");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));r.addHeader("Origin","https://localhost:8443");r.setContentType("application/json");return r; }
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code)); }
    @Test void writesUseVerifiedActorStrongEtagAndTrace() throws Exception {
        var r=request("PUT");r.addHeader("If-Match","\"1\"");r.addHeader("X-Principal-Id",ids.next().toString());r.addHeader("traceparent","00-"+"f".repeat(32)+"-"+"a".repeat(16)+"-01");r.setContent("{\"title\":\"제목\",\"markdown\":\"# 내용\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result=new NoteStore.Note(note,space,"PRIVATE","ACTIVE",2,"제목","# 내용",java.time.Instant.now(),java.time.Instant.now());when(store.change(space,note,actor,1,"제목","# 내용",false,"f".repeat(32))).thenReturn(result);
        var response=api.update(space,note,r);assertThat(response.getHeaders().getETag()).isEqualTo("\"2\"");assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");verify(store).change(space,note,actor,1,"제목","# 내용",false,"f".repeat(32));
    }
    @Test void missingWeakWildcardDuplicateOrStaleVersionCannotOverwrite() {
        status(428,()->api.update(space,note,request("PUT")));
        for(String tag:List.of("W/\"1\"","*","\"0\"","1","\"1\", \"2\"")) { var r=request("PUT");r.addHeader("If-Match",tag);status(400,()->api.update(space,note,r)); }
        verifyNoInteractions(store);
    }
    @Test void httpsOriginCredentialAndAuthorityFailuresNeverReachStore() {
        var http=request("POST");http.setSecure(false);http.addHeader("X-Forwarded-Proto","https");status(400,()->api.create(space,http));
        var cross=request("DELETE");cross.removeHeader("Origin");cross.addHeader("Origin","https://evil.example");status(403,()->api.retire(space,note,cross));
        var duplicate=request("GET");duplicate.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)),new Cookie("__Host-brain_guest","b".repeat(43)));status(401,()->api.get(space,note,duplicate));
        when(authority.authorize(eq(space),anyString(),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"unavailable"));status(503,()->api.get(space,note,request("GET")));verifyNoInteractions(store);
    }
    @Test void wikiReadsUseSpaceAuthorityAndNeverTrustCallerPrincipalHeaders() {
        var request=request("GET");request.addHeader("X-Principal-Id","spoof");when(store.outgoing(space,note,null,20)).thenReturn(new NoteLinkStore.Page(1,1,List.of(),null));assertThat(api.links(space,note,null,20,request).getHeaders().getCacheControl()).isEqualTo("no-store");verify(authority).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());
        when(authority.authorize(eq(space),anyString(),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Space unavailable"));status(404,()->api.backlinks(space,note,null,20,request));verify(store,never()).backlinks(any(),any(),any(),anyInt());
    }
    @Test void wikiInvalidCursorsAndPageLimitsAreRejected() {
        status(400,()->api.links(space,note,-1,20,request("GET")));status(400,()->api.links(space,note,null,101,request("GET")));status(400,()->api.backlinks(space,note,UUID.randomUUID(),20,request("GET")));verifyNoInteractions(store,authority);
    }
    @Test void malformedUnknownFieldsAndOversizeBodiesCannotCreate() {
        for(String body:List.of("{","[]","{\"title\":1,\"markdown\":\"\"}","{\"title\":\"a\",\"markdown\":\"\",\"visibility\":\"PUBLIC\"}")) {var r=request("POST");r.setContent(body.getBytes());status(400,()->api.create(space,r));}
        var large=request("POST");large.setContent(new byte[262145]);status(413,()->api.create(space,large));verifyNoInteractions(store);
    }

    @Test void retiredListingIsExplicitAuthorizedAndNoStoreWhileDefaultRemainsActive() {
        var empty=new NoteStore.Page(List.of(),null);when(store.list(space,null,20)).thenReturn(empty);when(store.retired(space,null,20)).thenReturn(empty);
        var normal=api.list(space,null,20,request("GET"));assertThat(normal.getBody()).isEqualTo(empty);
        var active=request("GET");active.setParameter("state","ACTIVE");api.list(space,null,20,active);
        var retired=request("GET");retired.setParameter("state","RETIRED");var response=api.list(space,null,20,retired);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(store,times(2)).list(space,null,20);verify(store).retired(space,null,20);verify(authority,times(3)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());
    }
    @Test void archiveQueriesRejectInvalidStateDuplicateUnknownOrEmptyParametersBeforeReading() {
        for(String state:List.of("ALL","retired",""," RETIRED","PUBLIC")){var r=request("GET");r.setParameter("state",state);status(400,()->api.list(space,null,20,r));}
        var duplicate=request("GET");duplicate.setParameter("state",new String[]{"RETIRED","ACTIVE"});status(400,()->api.list(space,null,20,duplicate));
        var unknown=request("GET");unknown.setParameter("spaceId",space.toString());status(400,()->api.list(space,null,20,unknown));
        var empty=request("GET");empty.setParameter("after","");status(400,()->api.list(space,null,20,empty));
        status(400,()->api.list(space,null,101,request("GET")));status(400,()->api.list(space,UUID.randomUUID(),20,request("GET")));verifyNoInteractions(store,authority);
    }
    @Test void archiveFilterDoesNotAlterItemHistoryOrWriteSemantics() {
        var read=request("GET");read.setParameter("state","RETIRED");status(400,()->api.get(space,note,read));status(400,()->api.history(space,note,100,5,read));status(400,()->api.metadata(space,note,read));status(400,()->api.links(space,note,null,20,read));
        for(String method:List.of("POST","PUT","DELETE")){var r=request(method);r.setParameter("state","RETIRED");status(400,()->{switch(method){case "POST"->api.create(space,r);case "PUT"->api.update(space,note,r);default->api.retire(space,note,r);}});}verifyNoInteractions(store,authority);
    }
    @Test void archiveReadsFailClosedOnRevocationForeignSpaceAndAuthorityOutage() {
        for(var code:List.of(org.springframework.http.HttpStatus.UNAUTHORIZED,org.springframework.http.HttpStatus.FORBIDDEN,org.springframework.http.HttpStatus.NOT_FOUND,org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)){
            when(authority.authorize(eq(space),anyString(),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(code));var r=request("GET");r.setParameter("state","RETIRED");status(code.value(),()->api.list(space,null,20,r));
        }verifyNoInteractions(store);
    }
}
