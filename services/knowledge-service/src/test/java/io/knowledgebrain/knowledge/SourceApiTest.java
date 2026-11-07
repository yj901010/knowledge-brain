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

class SourceApiTest {
    SourceStore store;
    SpaceAuthorizationClient authority;
    SourceController api;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,source,actor;
    String token="a".repeat(43),trace="f".repeat(32);
    String body="{\"title\":\"Manual source\",\"url\":\"https://example.org/reference\",\"citation\":\"Original citation\",\"licenseNote\":\"User supplied license note\"}";

    @BeforeEach void prepare() {
        space=ids.next();source=ids.next();actor=ids.next();store=mock(SourceStore.class);authority=mock(SpaceAuthorizationClient.class);
        var guard=new ConceptController(mock(ConceptStore.class),authority,new SimpleMeterRegistry(),"https://localhost:8443");
        api=new SourceController(store,guard,new SimpleMeterRegistry());
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    MockHttpServletRequest request(String method) {
        var r=new MockHttpServletRequest(method,"/api/spaces/"+space+"/sources");r.setSecure(true);
        r.setCookies(new Cookie("__Host-brain_guest",token));r.addHeader("Origin","https://localhost:8443");
        r.addHeader("Sec-Fetch-Site","same-origin");r.addHeader("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");
        r.setContentType("application/json");return r;
    }
    MockHttpServletRequest edit(String method,String tag,String content) {
        var r=request(method);if(tag!=null)r.addHeader("If-Match",tag);r.setContent(content.getBytes(StandardCharsets.UTF_8));return r;
    }
    SourceStore.Source value(long version,String state) {
        Instant now=Instant.now();return new SourceStore.Source(source,space,"Manual source","https://example.org/reference","Original citation","User supplied license note","PRIVATE",state,version,now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void retiredListingKeepsActiveDefaultAndFreshAuthority() {
        var page=new SourceStore.Page(List.of(value(2,"RETIRED")),null);when(store.retired(space,null,20)).thenReturn(page);
        var retired=request("GET");retired.addParameter("state","RETIRED");var response=api.list(space,retired);
        assertThat(response.getBody()).isEqualTo(page);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        api.list(space,request("GET"));var active=request("GET");active.addParameter("state","ACTIVE");api.list(space,active);
        verify(store).retired(space,null,20);verify(store,times(2)).list(space,null,20);verify(authority,times(3)).authorize(eq(space),eq(token),eq(false),anyString());
        reset(store);for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){
            doThrow(new ResponseStatusException(failure)).when(authority).authorize(eq(space),anyString(),eq(false),anyString());status(failure.value(),()->api.list(space,retired));
        }verifyNoInteractions(store);
    }
    @Test void retiredStateIsStrictAndCannotChangeOtherSourceOperations() {
        for(String value:List.of("","ALL","retired"," RETIRED")){var r=request("GET");r.addParameter("state",value);status(400,()->api.list(space,r));}
        var duplicate=request("GET");duplicate.addParameter("state","ACTIVE","RETIRED");status(400,()->api.list(space,duplicate));
        var r=request("GET");r.addParameter("state","RETIRED");status(400,()->api.get(space,source,r));status(400,()->api.history(space,source,r));
        status(400,()->api.create(space,r));status(400,()->api.update(space,source,r));status(400,()->api.retire(space,source,r));verifyNoInteractions(store,authority);
    }
    @Test void createUsesVerifiedActorAndIndependentSourceEtagLocationAndPrivacy() throws Exception {
        var r=edit("POST",null,body);r.addHeader("X-Principal-Id",ids.next().toString());
        when(store.create(space,actor,"Manual source","https://example.org/reference","Original citation","User supplied license note",trace)).thenReturn(value(1,"ACTIVE"));
        var response=api.create(space,r);assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"src1\"");
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/spaces/"+space+"/sources/"+source);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");
        verify(authority).authorize(eq(space),eq(token),eq(true),eq("00-"+trace+"-"+"a".repeat(16)+"-01"));
        verify(store).create(space,actor,"Manual source","https://example.org/reference","Original citation","User supplied license note",trace);
    }

    @Test void getUpdateAndRetireUseOnlyTheSourceVersion() throws Exception {
        when(store.get(space,source)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.get(space,source,request("GET")).getHeaders().getETag()).isEqualTo("\"src1\"");
        when(store.change(space,source,actor,1,"Manual source","https://example.org/reference","Original citation","User supplied license note",false,trace)).thenReturn(value(2,"ACTIVE"));
        var updated=api.update(space,source,edit("PUT","\"src1\"",body));assertThat(updated.getHeaders().getETag()).isEqualTo("\"src2\"");
        assertThat(updated.getHeaders().getCacheControl()).isEqualTo("no-store");
        when(store.change(space,source,actor,2,null,null,null,null,true,trace)).thenReturn(value(3,"RETIRED"));
        var retired=api.retire(space,source,edit("DELETE","\"src2\"",""));assertThat(retired.getStatusCode().value()).isEqualTo(204);
        assertThat(retired.getHeaders().getETag()).isEqualTo("\"src3\"");assertThat(retired.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test void citationOnlySourceUsesExplicitNullUrlWithoutAConceptParent() throws Exception {
        var r=edit("POST",null,"{\"title\":\"Printed book\",\"url\":null,\"citation\":\"Author, Title, 2026\",\"licenseNote\":\"\"}");
        when(store.create(space,actor,"Printed book",null,"Author, Title, 2026","",trace)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.create(space,r).getStatusCode().value()).isEqualTo(201);
        verify(store).create(space,actor,"Printed book",null,"Author, Title, 2026","",trace);
    }

    @Test void missingWeakOtherAggregateWildcardRepeatedAndOverflowTagsNeverWrite() {
        status(428,()->api.update(space,source,edit("PUT",null,body)));status(428,()->api.retire(space,source,edit("DELETE",null,"")));
        for(String tag:List.of("W/\"src1\"","*","\"1\"","\"c1\"","\"a1\"","\"cl1\"","\"src0\"","\"src01\"","src1","\"src1\", \"src2\"","\"src9223372036854775808\""))
            status(400,()->api.update(space,source,edit("PUT",tag,body)));
        var repeated=edit("DELETE","\"src1\"","");repeated.addHeader("If-Match","\"src2\"");status(400,()->api.retire(space,source,repeated));verifyNoInteractions(store);
    }

    @Test void strictJsonRejectsMissingUnknownNullWrongTypedDuplicateAndTrailingFields() {
        for(String content:List.of("{","[]","{}",body+" {}",body.replace("\"Manual source\"","null"),body.replace("\"Original citation\"","false"),
            body.replace("\"User supplied license note\"","[]"),body.replace("\"https://example.org/reference\"","42"),
            body.replace("\"title\":","\"title\":\"Injected\",\"title\":"),body.replace("\"url\":","\"url\":null,\"url\":"),
            body.substring(0,body.length()-1)+",\"visibility\":\"PUBLIC\"}",body.substring(0,body.length()-1)+",\"verificationState\":\"EXPERT_VERIFIED\"}",
            "{\"title\":\"Manual source\",\"citation\":\"Citation\",\"licenseNote\":\"\"}",
            "{\"title\":\"Manual source\",\"url\":null,\"citation\":\"Citation\"}",
            "{\"title\":\"Manual source\",\"url\":null,\"citation\":null,\"licenseNote\":\"\"}"))
            status(400,()->api.create(space,edit("POST",null,content)));
        var plain=edit("POST",null,body);plain.setContentType("text/plain");status(415,()->api.create(space,plain));
        var large=request("POST");large.setContent(new byte[32769]);status(413,()->api.create(space,large));verifyNoInteractions(store);
    }

    @Test void secureOriginCookieAndAuthorityFailuresNeverReachCanonicalSources() {
        var insecure=request("GET");insecure.setSecure(false);insecure.addHeader("X-Forwarded-Proto","https");status(400,()->api.get(space,source,insecure));
        var cross=edit("POST",null,body);cross.removeHeader("Origin");cross.addHeader("Origin","https://evil.example");status(403,()->api.create(space,cross));
        var site=edit("POST",null,body);site.removeHeader("Sec-Fetch-Site");site.addHeader("Sec-Fetch-Site","cross-site");status(403,()->api.create(space,site));
        var repeated=request("GET");repeated.setCookies(new Cookie("__Host-brain_guest",token),new Cookie("__Host-brain_guest","b".repeat(43)));status(401,()->api.get(space,source,repeated));
        for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure,"unavailable")).when(authority).authorize(eq(space),any(),anyBoolean(),anyString());
            status(failure.value(),()->api.history(space,source,request("GET")));
        }
        verifyNoInteractions(store);
    }

    @Test void unsafeUrlsInvalidUnicodeAndMissingProvenanceAreRejectedBeforeCanonicalWrites() {
        for(String url:List.of("ftp://example.org/file","//example.org/relative","https://user:password@example.org/","https://example.org:0/","https://example.org:65536/",
            "http://example.org:","http://[::1]:/","http://[fe80::1%25eth0]/","http://[fe80::1%eth0]/",
            "https://example.org/white space","https://example.org/한글","https://example.org/%zz"))
            status(400,()->api.create(space,edit("POST",null,body.replace("https://example.org/reference",url))));
        for(String title:List.of("","  ","😀".repeat(201)))status(400,()->api.create(space,edit("POST",null,body.replace("Manual source",title))));
        status(400,()->api.create(space,edit("POST",null,body.replace("Original citation","😀".repeat(4001)))));
        status(400,()->api.create(space,edit("POST",null,body.replace("User supplied license note","😀".repeat(1001)))));
        for(String escape:List.of("\\u0000","\\ud800","\\udc00")) {
            status(400,()->api.create(space,edit("POST",null,body.replace("Manual source","Bad "+escape))));
            status(400,()->api.create(space,edit("POST",null,body.replace("Original citation","Bad "+escape))));
            status(400,()->api.create(space,edit("POST",null,body.replace("User supplied license note","Bad "+escape))));
        }
        status(400,()->api.create(space,edit("POST",null,"{\"title\":\"License only\",\"url\":null,\"citation\":\"   \",\"licenseNote\":\"License is not provenance\"}")));
        verifyNoInteractions(store);
    }

    @Test void validUnicodeCodePointsAndOuterWhitespaceReachStoreAsCanonicalFields() throws Exception {
        String title="😀".repeat(200),citation="  😀 인용문  ",license="  사용자 주석  ";
        String content="{\"title\":\"  "+title+"  \",\"url\":\"  https://example.org/reference#section  \",\"citation\":\""+citation+"\",\"licenseNote\":\""+license+"\"}";
        when(store.create(space,actor,title,"https://example.org/reference#section",citation,license,trace)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.create(space,edit("POST",null,content)).getStatusCode().value()).isEqualTo(201);
        verify(store).create(space,actor,title,"https://example.org/reference#section",citation,license,trace);
    }

    @Test void listAndRetainedHistoryRequireSingleKnownBoundedKeysetQueries() {
        when(store.list(space,null,20)).thenReturn(new SourceStore.Page(List.of(),null));
        assertThat(api.list(space,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var page=request("GET");page.addParameter("after",source.toString());page.addParameter("limit","1");
        when(store.list(space,source,1)).thenReturn(new SourceStore.Page(List.of(value(1,"ACTIVE")),source));
        assertThat(api.list(space,page).getBody().nextAfter()).isEqualTo(source);
        when(store.history(space,source,Long.MAX_VALUE,5)).thenReturn(List.of());assertThat(api.history(space,source,request("GET")).getBody()).isEmpty();
        var history=request("GET");history.addParameter("before","3");history.addParameter("limit","1");
        when(store.history(space,source,3,1)).thenReturn(List.of());api.history(space,source,history);verify(store).history(space,source,3,1);
        clearInvocations(store);
        for(String limit:List.of("0","101","-1","01"," 1","1.0","2147483648","")){var r=request("GET");r.addParameter("limit",limit);status(400,()->api.list(space,r));}
        for(String before:List.of("0","-1","01","9223372036854775808","")){var r=request("GET");r.addParameter("before",before);status(400,()->api.history(space,source,r));}
        var historyLimit=request("GET");historyLimit.addParameter("limit","11");status(400,()->api.history(space,source,historyLimit));
        for(String name:List.of("visibility","spaceId","q","conceptId")){var r=request("GET");r.addParameter(name,"anything");status(400,()->api.list(space,r));}
        var repeated=request("GET");repeated.addParameter("limit","1","2");status(400,()->api.list(space,repeated));
        var wrongQuery=request("GET");wrongQuery.addParameter("after",source.toString());status(400,()->api.history(space,source,wrongQuery));
        var wrongCursor=request("GET");wrongCursor.addParameter("after",UUID.randomUUID().toString());status(400,()->api.list(space,wrongCursor));
        var uppercase=request("GET");uppercase.addParameter("after","ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF");status(400,()->api.list(space,uppercase));
        var itemQuery=request("GET");itemQuery.addParameter("limit","1");status(400,()->api.get(space,source,itemQuery));verifyNoInteractions(store);
    }

    @Test void invalidIdsAndForeignAuthorityNeverReadCanonicalSources() {
        status(400,()->api.get(space,UUID.randomUUID(),request("GET")));status(400,()->api.create(UUID.randomUUID(),edit("POST",null,body)));
        UUID foreign=ids.next();doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable")).when(authority).authorize(eq(foreign),eq(token),eq(false),anyString());
        status(404,()->api.get(foreign,source,request("GET")));verifyNoInteractions(store);
    }

    @Test void staleAndRetiredFailuresRemainActionableWithoutOverwritingDrafts() {
        for(HttpStatus failure:List.of(HttpStatus.PRECONDITION_FAILED,HttpStatus.NOT_FOUND)) {
            doThrow(new ResponseStatusException(failure,"Source unavailable")).when(store).change(space,source,actor,1,"Manual source","https://example.org/reference","Original citation","User supplied license note",false,trace);
            status(failure.value(),()->api.update(space,source,edit("PUT","\"src1\"",body)));
        }
    }
}
