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

class CitationApiTest {
    CitationStore store;
    SpaceAuthorizationClient authority;
    CitationController api;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,subject,claim,citation,source,actor;
    String token="a".repeat(43),trace="f".repeat(32),body,path;

    @BeforeEach void prepare() {
        space=ids.next();subject=ids.next();claim=ids.next();citation=ids.next();source=ids.next();actor=ids.next();
        path="/api/spaces/"+space+"/concepts/"+subject+"/claims/"+claim+"/citations";
        body="{\"sourceId\":\""+source+"\",\"sourceVersion\":1,\"claimVersion\":1}";
        store=mock(CitationStore.class);authority=mock(SpaceAuthorizationClient.class);
        var guard=new ConceptController(mock(ConceptStore.class),authority,new SimpleMeterRegistry(),"https://localhost:8443");
        api=new CitationController(store,guard,new SimpleMeterRegistry());
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    MockHttpServletRequest request(String method) {
        var r=new MockHttpServletRequest(method,path);r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest",token));
        r.addHeader("Origin","https://localhost:8443");r.addHeader("Sec-Fetch-Site","same-origin");
        r.addHeader("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");r.setContentType("application/json");return r;
    }
    MockHttpServletRequest edit(String method,String tag,String content) {
        var r=request(method);if(tag!=null)r.addHeader("If-Match",tag);r.setContent(content.getBytes(StandardCharsets.UTF_8));return r;
    }
    CitationStore.Reference ref() {return new CitationStore.Reference(source,1,1);}
    CitationStore.Citation value(long version,String state) {
        Instant now=Instant.now();
        var c=new CitationStore.ClaimSnapshot(ids.next(),1,subject,subject,new ClaimStore.ObjectValue("CONCEPT",subject));
        var s=new CitationStore.SourceSnapshot(source,ids.next(),1,"Pinned source",null,"Author, Book, 2026","Manual license note");
        return new CitationStore.Citation(citation,space,claim,subject,c,s,"PRIVATE",state,version,now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void retiredDiscoveryIsExplicitNoStoreAndReauthorizesEveryRead() {
        var r=request("GET");r.addParameter("state","RETIRED");r.addParameter("after",citation.toString());r.addParameter("limit","1");
        var archived=new CitationStore.Page(List.of(value(2,"RETIRED")),null);when(store.retired(space,subject,claim,citation,1)).thenReturn(archived);
        var response=api.list(space,subject,claim,r);assertThat(response.getBody()).isEqualTo(archived);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var active=request("GET");active.addParameter("state","ACTIVE");when(store.list(space,subject,claim,null,20)).thenReturn(new CitationStore.Page(List.of(),null));api.list(space,subject,claim,active);
        verify(authority,times(2)).authorize(eq(space),eq(token),eq(false),anyString());clearInvocations(store);
        when(authority.authorize(eq(space),eq(token),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.list(space,subject,claim,r));verifyNoInteractions(store);
    }
    @Test void lifecycleFilterIsStrictAndOnlyAcceptedOnCitationCollectionReads() {
        for(String state:List.of("","ALL","retired"," RETIRED","RETIRED ")){var r=request("GET");r.addParameter("state",state);status(400,()->api.list(space,subject,claim,r));}
        var repeated=request("GET");repeated.addParameter("state","ACTIVE","RETIRED");status(400,()->api.list(space,subject,claim,repeated));
        var read=request("GET");read.addParameter("state","RETIRED");status(400,()->api.get(space,subject,claim,citation,read));status(400,()->api.history(space,subject,claim,citation,read));
        var create=edit("POST",null,body);create.addParameter("state","RETIRED");status(400,()->api.create(space,subject,claim,create));
        var update=edit("PUT","\"cite1\"",body);update.addParameter("state","RETIRED");status(400,()->api.update(space,subject,claim,citation,update));
        var remove=edit("DELETE","\"cite1\"","");remove.addParameter("state","RETIRED");status(400,()->api.retire(space,subject,claim,citation,remove));verifyNoInteractions(store);
    }
    @Test void creationUsesVerifiedActorExactReferenceIndependentEtagLocationAndNoStore() throws Exception {
        var r=edit("POST",null,body);r.addHeader("X-Principal-Id",ids.next().toString());
        when(store.create(space,subject,claim,actor,ref(),trace)).thenReturn(value(1,"ACTIVE"));
        var response=api.create(space,subject,claim,r);assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"cite1\"");assertThat(response.getHeaders().getLocation().toString()).isEqualTo(path+"/"+citation);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");
        verify(authority).authorize(eq(space),eq(token),eq(true),eq("00-"+trace+"-"+"a".repeat(16)+"-01"));
        verify(store).create(space,subject,claim,actor,ref(),trace);
    }

    @Test void getRepinAndRetireUseOnlyCitationEtagAndRetireHasNoProposedReference() throws Exception {
        when(store.get(space,subject,claim,citation)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.get(space,subject,claim,citation,request("GET")).getHeaders().getETag()).isEqualTo("\"cite1\"");
        when(store.change(space,subject,claim,citation,actor,1,ref(),false,trace)).thenReturn(value(2,"ACTIVE"));
        var changed=api.update(space,subject,claim,citation,edit("PUT","\"cite1\"",body));
        assertThat(changed.getHeaders().getETag()).isEqualTo("\"cite2\"");assertThat(changed.getHeaders().getCacheControl()).isEqualTo("no-store");
        when(store.change(space,subject,claim,citation,actor,2,null,true,trace)).thenReturn(value(3,"RETIRED"));
        var retired=api.retire(space,subject,claim,citation,edit("DELETE","\"cite2\"",""));
        assertThat(retired.getStatusCode().value()).isEqualTo(204);assertThat(retired.getHeaders().getETag()).isEqualTo("\"cite3\"");
        assertThat(retired.getHeaders().getCacheControl()).isEqualTo("no-store");verify(store).change(space,subject,claim,citation,actor,2,null,true,trace);
    }

    @Test void missingWeakOtherAggregateWildcardRepeatedAndOverflowEtagsNeverMutate() {
        status(428,()->api.update(space,subject,claim,citation,edit("PUT",null,body)));
        status(428,()->api.retire(space,subject,claim,citation,edit("DELETE",null,"")));
        for(String tag:List.of("W/\"cite1\"","*","\"1\"","\"c1\"","\"a1\"","\"cl1\"","\"src1\"","\"cite0\"","\"cite01\"","cite1","\"cite1\", \"cite2\"","\"cite9223372036854775808\""))
            status(400,()->api.update(space,subject,claim,citation,edit("PUT",tag,body)));
        var repeated=edit("DELETE","\"cite1\"","");repeated.addHeader("If-Match","\"cite2\"");
        status(400,()->api.retire(space,subject,claim,citation,repeated));verifyNoInteractions(store);
    }

    @Test void retireRejectsAnyInputBodyWithoutTouchingCitationEvenWithValidVersion() {
        for(String content:List.of(" ","{}",body,"null"))
            status(400,()->api.retire(space,subject,claim,citation,edit("DELETE","\"cite1\"",content)));
        verifyNoInteractions(store);
    }

    @Test void strictJsonRejectsUnknownMissingDuplicateTrailingAndWrongReferenceTypes() {
        for(String content:List.of("{","[]","{}",body+" {}",body.replace("\"sourceVersion\":1","\"sourceVersion\":null"),
            body.replace("\"sourceVersion\":1","\"sourceVersion\":\"1\""),body.replace("\"sourceVersion\":1","\"sourceVersion\":1.0"),
            body.replace("\"claimVersion\":1","\"claimVersion\":true"),body.replace("\"sourceId\":\""+source+"\"","\"sourceId\":null"),
            body.replace("\"sourceId\":","\"sourceId\":\""+source+"\",\"sourceId\":"),
            body.replace("\"claimVersion\":1","\"claimVersion\":1,\"claimVersion\":2"),
            body.substring(0,body.length()-1)+",\"verificationState\":\"SOURCED\"}",
            "{\"sourceId\":\""+source+"\",\"claimVersion\":1}","{\"sourceId\":\""+source+"\",\"sourceVersion\":1}"))
            status(400,()->api.create(space,subject,claim,edit("POST",null,content)));
        var plain=edit("POST",null,body);plain.setContentType("text/plain");status(415,()->api.create(space,subject,claim,plain));
        var oversized=request("POST");oversized.setContent(new byte[4097]);status(413,()->api.create(space,subject,claim,oversized));
        verifyNoInteractions(store);
    }

    @Test void canonicalUuidAndPositiveIntegerRevisionPinsAreValidatedBeforeStore() {
        for(String version:List.of("0","-1","9223372036854775808","1e0","[]","{}")) {
            status(400,()->api.create(space,subject,claim,edit("POST",null,body.replace("\"sourceVersion\":1","\"sourceVersion\":"+version))));
            status(400,()->api.create(space,subject,claim,edit("POST",null,body.replace("\"claimVersion\":1","\"claimVersion\":"+version))));
        }
        for(String id:List.of(UUID.randomUUID().toString(),"ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF","invalid","1-1-1-1-1"))
            status(400,()->api.create(space,subject,claim,edit("POST",null,body.replace(source.toString(),id))));
        status(400,()->api.create(UUID.randomUUID(),subject,claim,edit("POST",null,body)));
        status(400,()->api.create(space,UUID.randomUUID(),claim,edit("POST",null,body)));
        status(400,()->api.create(space,subject,UUID.randomUUID(),edit("POST",null,body)));
        status(400,()->api.get(space,subject,claim,UUID.randomUUID(),request("GET")));verifyNoInteractions(store);
    }

    @Test void maximumPositiveLongPinsRemainExactWithoutFloatingPointConversion() throws Exception {
        var maximum=new CitationStore.Reference(source,Long.MAX_VALUE,Long.MAX_VALUE);
        String content=body.replace("\"sourceVersion\":1","\"sourceVersion\":"+Long.MAX_VALUE).replace("\"claimVersion\":1","\"claimVersion\":"+Long.MAX_VALUE);
        when(store.create(space,subject,claim,actor,maximum,trace)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.create(space,subject,claim,edit("POST",null,content)).getStatusCode().value()).isEqualTo(201);
        verify(store).create(space,subject,claim,actor,maximum,trace);
    }

    @Test void strictListAndRetainedHistoryKeysetsHaveSeparateKnownBoundedQueries() throws Exception {
        when(store.list(space,subject,claim,null,20)).thenReturn(new CitationStore.Page(List.of(),null));
        assertThat(api.list(space,subject,claim,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var page=request("GET");page.addParameter("after",citation.toString());page.addParameter("limit","1");
        when(store.list(space,subject,claim,citation,1)).thenReturn(new CitationStore.Page(List.of(value(1,"ACTIVE")),citation));
        assertThat(api.list(space,subject,claim,page).getBody().nextAfter()).isEqualTo(citation);
        when(store.history(space,subject,claim,citation,Long.MAX_VALUE,5)).thenReturn(List.of());
        assertThat(api.history(space,subject,claim,citation,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var history=request("GET");history.addParameter("before","3");history.addParameter("limit","1");
        when(store.history(space,subject,claim,citation,3,1)).thenReturn(List.of());api.history(space,subject,claim,citation,history);
        clearInvocations(store);
        for(String limit:List.of("0","101","-1","01"," 1","1.0","2147483648","")) {var r=request("GET");r.addParameter("limit",limit);status(400,()->api.list(space,subject,claim,r));}
        for(String before:List.of("0","-1","01","9223372036854775808","")) {var r=request("GET");r.addParameter("before",before);status(400,()->api.history(space,subject,claim,citation,r));}
        var largeHistory=request("GET");largeHistory.addParameter("limit","11");status(400,()->api.history(space,subject,claim,citation,largeHistory));
        for(String name:List.of("sourceId","claimVersion","before","visibility","q")) {var r=request("GET");r.addParameter(name,"anything");status(400,()->api.list(space,subject,claim,r));}
        var repeated=request("GET");repeated.addParameter("limit","1","2");status(400,()->api.list(space,subject,claim,repeated));
        for(String after:List.of(UUID.randomUUID().toString(),"ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF","")) {var r=request("GET");r.addParameter("after",after);status(400,()->api.list(space,subject,claim,r));}
        var wrong=request("GET");wrong.addParameter("after",citation.toString());status(400,()->api.history(space,subject,claim,citation,wrong));
        var itemQuery=request("GET");itemQuery.addParameter("limit","1");status(400,()->api.get(space,subject,claim,citation,itemQuery));verifyNoInteractions(store);
    }

    @Test void httpsOriginUniqueCookieAndFreshSpaceAuthorityAreRequiredForPinnedHistoryToo() {
        var insecure=request("GET");insecure.setSecure(false);insecure.addHeader("X-Forwarded-Proto","https");status(400,()->api.get(space,subject,claim,citation,insecure));
        var cross=edit("POST",null,body);cross.removeHeader("Origin");cross.addHeader("Origin","https://evil.example");status(403,()->api.create(space,subject,claim,cross));
        var site=edit("POST",null,body);site.removeHeader("Sec-Fetch-Site");site.addHeader("Sec-Fetch-Site","cross-site");status(403,()->api.create(space,subject,claim,site));
        var repeated=request("GET");repeated.setCookies(new Cookie("__Host-brain_guest",token),new Cookie("__Host-brain_guest","b".repeat(43)));
        status(401,()->api.history(space,subject,claim,citation,repeated));
        for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure,"denied")).when(authority).authorize(eq(space),any(),anyBoolean(),anyString());
            status(failure.value(),()->api.history(space,subject,claim,citation,request("GET")));
        }
        verifyNoInteractions(store);
    }

    @Test void staleCitationClaimSourceDuplicateAndUnavailableErrorsRemainActionable() {
        for(HttpStatus failure:List.of(HttpStatus.PRECONDITION_FAILED,HttpStatus.CONFLICT,HttpStatus.NOT_FOUND)) {
            doThrow(new ResponseStatusException(failure,"Reference changed")).when(store).change(space,subject,claim,citation,actor,1,ref(),false,trace);
            status(failure.value(),()->api.update(space,subject,claim,citation,edit("PUT","\"cite1\"",body)));
        }
    }
}
