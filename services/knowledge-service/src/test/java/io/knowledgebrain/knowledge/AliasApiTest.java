package io.knowledgebrain.knowledge;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AliasApiTest {
    AliasStore store;
    SpaceAuthorizationClient authority;
    AliasController api;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,concept,alias,actor;
    String token="a".repeat(43),trace="f".repeat(32);

    @BeforeEach void prepare() {
        space=ids.next();concept=ids.next();alias=ids.next();actor=ids.next();
        store=mock(AliasStore.class);authority=mock(SpaceAuthorizationClient.class);
        var guard=new ConceptController(mock(ConceptStore.class),authority,new SimpleMeterRegistry(),"https://localhost:8443");
        api=new AliasController(store,guard,new SimpleMeterRegistry());
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    MockHttpServletRequest request(String method) {
        var r=new MockHttpServletRequest(method,"/api/spaces/"+space+"/concepts/"+concept+"/aliases");
        r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest",token));
        r.addHeader("Origin","https://localhost:8443");r.addHeader("Sec-Fetch-Site","same-origin");
        r.addHeader("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");r.setContentType("application/json");return r;
    }
    MockHttpServletRequest edit(String method,String tag,String body) {
        var r=request(method);if(tag!=null)r.addHeader("If-Match",tag);r.setContent(body.getBytes(StandardCharsets.UTF_8));return r;
    }
    AliasStore.Alias value(long version,String state) {
        Instant now=Instant.now();return new AliasStore.Alias(alias,concept,space,"PRIVATE","SPACE",state,version,"자바",now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void createUsesVerifiedActorAndOwnEtagLocationAndPrivacy() throws Exception {
        var r=edit("POST",null,"{\"label\":\"자바\"}");r.addHeader("X-Principal-Id",ids.next().toString());
        when(store.create(space,concept,actor,"자바",trace)).thenReturn(value(1,"ACTIVE"));
        var response=api.create(space,concept,r);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"a1\"");
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/spaces/"+space+"/concepts/"+concept+"/aliases/"+alias);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");assertThat(response.getBody().scope()).isEqualTo("SPACE");
        verify(authority).authorize(eq(space),eq(token),eq(true),eq("00-"+trace+"-"+"a".repeat(16)+"-01"));
        verify(store).create(space,concept,actor,"자바",trace);
    }

    @Test void readUpdateAndRetireKeepAliasVersionSeparate() throws Exception {
        when(store.get(space,concept,alias)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.get(space,concept,alias,request("GET")).getHeaders().getETag()).isEqualTo("\"a1\"");
        when(store.change(space,concept,alias,actor,1,"자바",false,trace)).thenReturn(value(2,"ACTIVE"));
        var update=api.update(space,concept,alias,edit("PUT","\"a1\"","{\"label\":\"자바\"}"));
        assertThat(update.getStatusCode().value()).isEqualTo(200);assertThat(update.getHeaders().getETag()).isEqualTo("\"a2\"");
        when(store.change(space,concept,alias,actor,2,null,true,trace)).thenReturn(value(3,"RETIRED"));
        var retire=api.retire(space,concept,alias,edit("DELETE","\"a2\"",""));
        assertThat(retire.getStatusCode().value()).isEqualTo(204);assertThat(retire.getHeaders().getETag()).isEqualTo("\"a3\"");
        assertThat(retire.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test void missingWeakOtherAggregateWildcardDuplicateAndOverflowTagsNeverWrite() {
        status(428,()->api.update(space,concept,alias,edit("PUT",null,"{\"label\":\"a\"}")));
        for(String tag:List.of("W/\"a1\"","*","\"1\"","\"c1\"","\"a0\"","a1","\"a1\", \"a2\"","\"a9223372036854775808\"")) {
            status(400,()->api.update(space,concept,alias,edit("PUT",tag,"{\"label\":\"a\"}")));
        }
        var duplicate=edit("DELETE","\"a1\"","");duplicate.addHeader("If-Match","\"a2\"");
        status(400,()->api.retire(space,concept,alias,duplicate));verifyNoInteractions(store);
    }

    @Test void httpsOriginCookieAndAuthorityFailuresNeverReachCanonicalData() {
        var http=request("GET");http.setSecure(false);http.addHeader("X-Forwarded-Proto","https");
        status(400,()->api.get(space,concept,alias,http));
        var cross=edit("POST",null,"{\"label\":\"a\"}");cross.removeHeader("Origin");cross.addHeader("Origin","https://evil.example");
        status(403,()->api.create(space,concept,cross));
        var site=edit("POST",null,"{\"label\":\"a\"}");site.removeHeader("Sec-Fetch-Site");site.addHeader("Sec-Fetch-Site","cross-site");
        status(403,()->api.create(space,concept,site));
        var duplicate=request("GET");duplicate.setCookies(new Cookie("__Host-brain_guest",token),new Cookie("__Host-brain_guest","b".repeat(43)));
        status(401,()->api.get(space,concept,alias,duplicate));
        for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure,"unavailable")).when(authority).authorize(eq(space),any(),anyBoolean(),anyString());
            status(failure.value(),()->api.history(space,concept,alias,request("GET")));
        }
        verifyNoInteractions(store);
    }

    @Test void malformedDuplicateTrailingUnknownFieldsAndWrongContentTypeCannotCreate() {
        for(String body:List.of("{","[]","{\"label\":1}","{}","{\"label\":\"a\",\"visibility\":\"PUBLIC\"}",
                "{\"label\":\"a\",\"scope\":\"GLOBAL\"}","{\"label\":\"a\",\"label\":\"b\"}","{\"label\":\"a\"} {}")) {
            status(400,()->api.create(space,concept,edit("POST",null,body)));
        }
        var plain=edit("POST",null,"{\"label\":\"a\"}");plain.setContentType("text/plain");status(415,()->api.create(space,concept,plain));
        var large=request("POST");large.setContent(new byte[262145]);status(413,()->api.create(space,concept,large));verifyNoInteractions(store);
    }

    @Test void listsAndHistoryUseStrictBoundedSingleQueryParameters() {
        when(store.list(space,concept,null,20)).thenReturn(new AliasStore.Page(List.of(),null));
        assertThat(api.list(space,concept,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var page=request("GET");page.addParameter("after",alias.toString());page.addParameter("limit","1");
        when(store.list(space,concept,alias,1)).thenReturn(new AliasStore.Page(List.of(value(1,"ACTIVE")),alias));
        assertThat(api.list(space,concept,page).getBody().nextAfter()).isEqualTo(alias);
        when(store.history(space,concept,alias,Long.MAX_VALUE,5)).thenReturn(List.of());
        assertThat(api.history(space,concept,alias,request("GET")).getBody()).isEmpty();
        var history=request("GET");history.addParameter("before","3");history.addParameter("limit","1");
        when(store.history(space,concept,alias,3,1)).thenReturn(List.of());api.history(space,concept,alias,history);
        verify(store).history(space,concept,alias,3,1);
        clearInvocations(store);
        for(String limit:List.of("0","101","-1","01"," 1","1.0","2147483648","")) {
            var r=request("GET");r.addParameter("limit",limit);status(400,()->api.list(space,concept,r));
        }
        for(String before:List.of("0","-1","01","9223372036854775808","")) {
            var r=request("GET");r.addParameter("before",before);status(400,()->api.history(space,concept,alias,r));
        }
        var tooMany=request("GET");tooMany.addParameter("limit","11");status(400,()->api.history(space,concept,alias,tooMany));
        for(String name:List.of("visibility","spaceId","q")) {
            var r=request("GET");r.addParameter(name,"anything");status(400,()->api.list(space,concept,r));
        }
        var repeated=request("GET");repeated.addParameter("limit","1","2");status(400,()->api.list(space,concept,repeated));
        var wrongQuery=request("GET");wrongQuery.addParameter("after",alias.toString());status(400,()->api.history(space,concept,alias,wrongQuery));
        var wrongCursor=request("GET");wrongCursor.addParameter("after",UUID.randomUUID().toString());status(400,()->api.list(space,concept,wrongCursor));
        var uppercase=request("GET");uppercase.addParameter("after","ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF");status(400,()->api.list(space,concept,uppercase));
        verifyNoInteractions(store);
    }

    @Test void invalidDomainIdentifiersCannotReadOrWrite() {
        status(400,()->api.get(space,UUID.randomUUID(),alias,request("GET")));
        status(400,()->api.get(space,concept,UUID.randomUUID(),request("GET")));
        status(400,()->api.create(UUID.randomUUID(),concept,edit("POST",null,"{\"label\":\"a\"}")));
        verifyNoInteractions(store);
    }

    @Test void duplicateStaleRetiredAndForeignFailuresKeepStatusAndNeverExposeForeignRecord() {
        for(HttpStatus failure:List.of(HttpStatus.CONFLICT,HttpStatus.PRECONDITION_FAILED,HttpStatus.NOT_FOUND)) {
            doThrow(new ResponseStatusException(failure,"Alias unavailable")).when(store).change(space,concept,alias,actor,1,"a",false,trace);
            status(failure.value(),()->api.update(space,concept,alias,edit("PUT","\"a1\"","{\"label\":\"a\"}")));
        }
        var foreign=request("GET");UUID other=ids.next();
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable")).when(authority).authorize(eq(other),eq(token),eq(false),anyString());
        clearInvocations(store);status(404,()->api.get(other,concept,alias,foreign));verifyNoInteractions(store);
    }

    @Test void retiredCollectionIsExplicitNoStoreAndRechecksAuthority() {
        var row=value(2,"RETIRED");var query=request("GET");query.addParameter("state","RETIRED");query.addParameter("after",alias.toString());query.addParameter("limit","1");
        when(store.retired(space,concept,alias,1)).thenReturn(new AliasStore.Page(List.of(row),alias));
        var response=api.list(space,concept,query);assertThat(response.getBody().items()).containsExactly(row);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var active=request("GET");active.addParameter("state","ACTIVE");when(store.list(space,concept,null,20)).thenReturn(new AliasStore.Page(List.of(),null));api.list(space,concept,active);api.list(space,concept,request("GET"));verify(store,times(2)).list(space,concept,null,20);
        clearInvocations(store);for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure)).when(authority).authorize(eq(space),eq(token),eq(false),anyString());status(failure.value(),()->api.list(space,concept,query));
        }verifyNoInteractions(store);
    }
    @Test void stateFilterCannotBeDuplicatedOrUsedOnOtherOperations() {
        for(String state:List.of("","ALL","retired"," RETIRED","RETIRED ")){var q=request("GET");q.addParameter("state",state);status(400,()->api.list(space,concept,q));}
        var repeated=request("GET");repeated.addParameter("state","RETIRED","ACTIVE");status(400,()->api.list(space,concept,repeated));
        var get=request("GET");get.addParameter("state","RETIRED");status(400,()->api.get(space,concept,alias,get));status(400,()->api.history(space,concept,alias,get));
        var post=edit("POST",null,"{\"label\":\"Example\"}");post.addParameter("state","RETIRED");status(400,()->api.create(space,concept,post));
        var put=edit("PUT","\"a1\"","{\"label\":\"Example\"}");put.addParameter("state","RETIRED");status(400,()->api.update(space,concept,alias,put));
        var delete=edit("DELETE","\"a1\"","");delete.addParameter("state","RETIRED");status(400,()->api.retire(space,concept,alias,delete));verifyNoInteractions(store);
    }
}
