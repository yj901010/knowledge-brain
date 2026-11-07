package io.knowledgebrain.knowledge;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ConceptApiTest {
    ConceptStore store;SpaceAuthorizationClient authority;ConceptController api;UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor,note;
    @BeforeEach void prepare() { store=mock(ConceptStore.class);authority=mock(SpaceAuthorizationClient.class);api=new ConceptController(store,authority,new SimpleMeterRegistry(),"https://localhost:8443");space=ids.next();actor=ids.next();note=ids.next();when(authority.authorize(eq(space),eq("a".repeat(43)),anyBoolean(),anyString())).thenReturn(actor); }
    MockHttpServletRequest request(String method) { var r=new MockHttpServletRequest(method,"/api/spaces/"+space+"/concepts");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));r.addHeader("Origin","https://localhost:8443");r.setContentType("application/json");return r; }
    void status(int code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) { assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(code)); }
    @Test void retiredListingUsesFreshAuthorityAndKeepsDefaultActive() {
        var page=new ConceptStore.Page(List.of(new ConceptStore.Summary(note,"Retired",2,java.time.Instant.now())),null);
        when(store.retired(space,null,20)).thenReturn(page);
        var retired=request("GET");retired.addParameter("state","RETIRED");
        assertThat(api.list(space,null,20,retired).getBody()).isEqualTo(page);
        assertThat(api.list(space,null,20,retired).getHeaders().getCacheControl()).isEqualTo("no-store");
        api.list(space,null,20,request("GET"));var active=request("GET");active.addParameter("state","ACTIVE");api.list(space,null,20,active);
        verify(store,times(2)).retired(space,null,20);verify(store,times(2)).list(space,null,20);
        verify(authority,times(4)).authorize(eq(space),eq("a".repeat(43)),eq(false),anyString());
        reset(store);when(authority.authorize(eq(space),anyString(),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        status(401,()->api.list(space,null,20,retired));verifyNoInteractions(store);
    }
    @Test void retiredFilterIsStrictAndCannotChangeOtherOperations() {
        for(String value:List.of("","ALL","retired")){var r=request("GET");r.addParameter("state",value);status(400,()->api.list(space,null,20,r));}
        var duplicate=request("GET");duplicate.addParameter("state","ACTIVE","RETIRED");status(400,()->api.list(space,null,20,duplicate));
        var unknown=request("GET");unknown.addParameter("visibility","PUBLIC");status(400,()->api.list(space,null,20,unknown));
        var r=request("GET");r.addParameter("state","RETIRED");
        status(400,()->api.get(space,note,r));status(400,()->api.history(space,note,Long.MAX_VALUE,5,r));
        status(400,()->api.create(space,r));status(400,()->api.update(space,note,r));status(400,()->api.retire(space,note,r));
        status(400,()->api.list(space,null,0,r));status(400,()->api.list(space,null,101,r));status(400,()->api.list(space,UUID.randomUUID(),20,r));
        verifyNoInteractions(store,authority);
    }
    @Test void writesUseVerifiedActorStrongEtagAndTrace() throws Exception {
        var r=request("PUT");r.addHeader("If-Match","\"1\"");r.addHeader("X-Principal-Id",ids.next().toString());r.addHeader("traceparent","00-"+"f".repeat(32)+"-"+"a".repeat(16)+"-01");r.setContent("{\"title\":\"제목\",\"description\":\"# 내용\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result=new ConceptStore.Concept(note,space,"PRIVATE","ACTIVE",2,"제목","# 내용",java.time.Instant.now(),java.time.Instant.now());when(store.change(space,note,actor,1,"제목","# 내용",false,"f".repeat(32))).thenReturn(result);
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
    @Test void malformedUnknownFieldsAndOversizeBodiesCannotCreate() {
        for(String body:List.of("{","[]","{\"title\":1,\"description\":\"\"}","{\"title\":\"a\",\"description\":\"\",\"visibility\":\"PUBLIC\"}")) {var r=request("POST");r.setContent(body.getBytes());status(400,()->api.create(space,r));}
        var large=request("POST");large.setContent(new byte[262145]);status(413,()->api.create(space,large));verifyNoInteractions(store);
    }
}
