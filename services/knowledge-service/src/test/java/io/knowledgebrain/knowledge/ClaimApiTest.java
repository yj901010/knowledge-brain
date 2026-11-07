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

class ClaimApiTest {
    ClaimStore store;
    SpaceAuthorizationClient authority;
    ClaimController api;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,subject,predicate,object,claim,actor;
    String token="a".repeat(43),trace="f".repeat(32);

    @BeforeEach void prepare() {
        space=ids.next();subject=ids.next();predicate=ids.next();object=ids.next();claim=ids.next();actor=ids.next();
        store=mock(ClaimStore.class);authority=mock(SpaceAuthorizationClient.class);
        var guard=new ConceptController(mock(ConceptStore.class),authority,new SimpleMeterRegistry(),"https://localhost:8443");
        api=new ClaimController(store,guard,new SimpleMeterRegistry());
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    MockHttpServletRequest request(String method) {
        var r=new MockHttpServletRequest(method,"/api/spaces/"+space+"/concepts/"+subject+"/claims");
        r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest",token));
        r.addHeader("Origin","https://localhost:8443");r.addHeader("Sec-Fetch-Site","same-origin");
        r.addHeader("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");r.setContentType("application/json");return r;
    }
    String body(UUID p,UUID o) {return "{\"predicateConceptId\":\""+p+"\",\"object\":{\"type\":\"CONCEPT\",\"conceptId\":\""+o+"\"}}";}
    ClaimStore.ObjectValue objectValue() {return new ClaimStore.ObjectValue("CONCEPT",object);}
    MockHttpServletRequest edit(String method,String tag,String body) {
        var r=request(method);if(tag!=null)r.addHeader("If-Match",tag);r.setContent(body.getBytes(StandardCharsets.UTF_8));return r;
    }
    ClaimStore.Claim value(long version,String state) {
        Instant now=Instant.now();return new ClaimStore.Claim(claim,space,subject,predicate,objectValue(),"PRIVATE",state,version,"DRAFT",now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void createUsesVerifiedActorAndOwnEtagLocationAndPrivateDraftState() throws Exception {
        var r=edit("POST",null,body(predicate,object));r.addHeader("X-Principal-Id",ids.next().toString());
        when(store.create(space,subject,actor,predicate,objectValue(),trace)).thenReturn(value(1,"ACTIVE"));
        var response=api.create(space,subject,r);
        assertThat(response.getStatusCode().value()).isEqualTo(201);assertThat(response.getHeaders().getETag()).isEqualTo("\"cl1\"");
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/spaces/"+space+"/concepts/"+subject+"/claims/"+claim);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");assertThat(response.getBody().verificationState()).isEqualTo("DRAFT");
        verify(authority).authorize(eq(space),eq(token),eq(true),eq("00-"+trace+"-"+"a".repeat(16)+"-01"));
        verify(store).create(space,subject,actor,predicate,objectValue(),trace);
    }

    @Test void readUpdateAndRetireUseIndependentClaimVersion() throws Exception {
        when(store.get(space,subject,claim)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.get(space,subject,claim,request("GET")).getHeaders().getETag()).isEqualTo("\"cl1\"");
        when(store.change(space,subject,claim,actor,1,predicate,objectValue(),false,trace)).thenReturn(value(2,"ACTIVE"));
        assertThat(api.update(space,subject,claim,edit("PUT","\"cl1\"",body(predicate,object))).getHeaders().getETag()).isEqualTo("\"cl2\"");
        when(store.change(space,subject,claim,actor,2,null,null,true,trace)).thenReturn(value(3,"RETIRED"));
        var response=api.retire(space,subject,claim,edit("DELETE","\"cl2\"",""));
        assertThat(response.getStatusCode().value()).isEqualTo(204);assertThat(response.getHeaders().getETag()).isEqualTo("\"cl3\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test void missingWeakOtherAggregateWildcardDuplicateAndOverflowTagsNeverWrite() {
        status(428,()->api.update(space,subject,claim,edit("PUT",null,body(predicate,object))));
        for(String tag:List.of("W/\"cl1\"","*","\"1\"","\"c1\"","\"a1\"","\"cl0\"","cl1","\"cl1\", \"cl2\"","\"cl9223372036854775808\""))
            status(400,()->api.update(space,subject,claim,edit("PUT",tag,body(predicate,object))));
        var duplicate=edit("DELETE","\"cl1\"","");duplicate.addHeader("If-Match","\"cl2\"");
        status(400,()->api.retire(space,subject,claim,duplicate));verifyNoInteractions(store);
    }

    @Test void httpsOriginCookieAndAuthorityFailuresNeverReachCanonicalClaims() {
        var http=request("GET");http.setSecure(false);http.addHeader("X-Forwarded-Proto","https");status(400,()->api.get(space,subject,claim,http));
        var cross=edit("POST",null,body(predicate,object));cross.removeHeader("Origin");cross.addHeader("Origin","https://evil.example");status(403,()->api.create(space,subject,cross));
        var site=edit("POST",null,body(predicate,object));site.removeHeader("Sec-Fetch-Site");site.addHeader("Sec-Fetch-Site","cross-site");status(403,()->api.create(space,subject,site));
        var duplicate=request("GET");duplicate.setCookies(new Cookie("__Host-brain_guest",token),new Cookie("__Host-brain_guest","b".repeat(43)));
        status(401,()->api.get(space,subject,claim,duplicate));
        for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure,"unavailable")).when(authority).authorize(eq(space),any(),anyBoolean(),anyString());
            status(failure.value(),()->api.history(space,subject,claim,request("GET")));
        }
        verifyNoInteractions(store);
    }

    @Test void strictTypedObjectRejectsMalformedDuplicateTrailingUnknownAndNonCanonicalIds() {
        String valid=body(predicate,object);
        for(String body:List.of("{","[]","{}",valid+" {}",valid.replace("\"CONCEPT\"","\"STRING\""),
                valid.replace("\"CONCEPT\"","1"),valid.replace("\"object\":{","\"object\":{\"visibility\":\"PUBLIC\","),
                valid.replace("\"type\":\"CONCEPT\"","\"type\":\"CONCEPT\",\"type\":\"CONCEPT\""),
                valid.replace("\"predicateConceptId\":","\"predicateConceptId\":\""+predicate+"\",\"predicateConceptId\":"),
                valid.substring(0,valid.length()-1)+",\"verificationState\":\"EXPERT_VERIFIED\"}",
                valid.substring(0,valid.length()-1)+",\"subjectConceptId\":\""+ids.next()+"\"}",
                valid.replace(predicate.toString(),UUID.randomUUID().toString()),valid.replace(object.toString(),UUID.randomUUID().toString()),
                valid.replace(object.toString(),"ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF"),
                "{\"predicateConceptId\":\""+predicate+"\",\"object\":null}"))
            status(400,()->api.create(space,subject,edit("POST",null,body)));
        var plain=edit("POST",null,valid);plain.setContentType("text/plain");status(415,()->api.create(space,subject,plain));
        var large=request("POST");large.setContent(new byte[16385]);status(413,()->api.create(space,subject,large));verifyNoInteractions(store);
    }

    @Test void listsAndHistoryRequireSingleKnownBoundedKeysetQueries() {
        when(store.list(space,subject,null,20)).thenReturn(new ClaimStore.Page(List.of(),null));
        assertThat(api.list(space,subject,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var page=request("GET");page.addParameter("after",claim.toString());page.addParameter("limit","1");
        when(store.list(space,subject,claim,1)).thenReturn(new ClaimStore.Page(List.of(value(1,"ACTIVE")),claim));
        assertThat(api.list(space,subject,page).getBody().nextAfter()).isEqualTo(claim);
        when(store.history(space,subject,claim,Long.MAX_VALUE,5)).thenReturn(List.of());
        assertThat(api.history(space,subject,claim,request("GET")).getBody()).isEmpty();
        var history=request("GET");history.addParameter("before","3");history.addParameter("limit","1");
        when(store.history(space,subject,claim,3,1)).thenReturn(List.of());api.history(space,subject,claim,history);verify(store).history(space,subject,claim,3,1);
        clearInvocations(store);
        for(String limit:List.of("0","101","-1","01"," 1","1.0","2147483648","")) {var r=request("GET");r.addParameter("limit",limit);status(400,()->api.list(space,subject,r));}
        for(String before:List.of("0","-1","01","9223372036854775808","")) {var r=request("GET");r.addParameter("before",before);status(400,()->api.history(space,subject,claim,r));}
        var tooMany=request("GET");tooMany.addParameter("limit","11");status(400,()->api.history(space,subject,claim,tooMany));
        for(String name:List.of("visibility","spaceId","q")) {var r=request("GET");r.addParameter(name,"anything");status(400,()->api.list(space,subject,r));}
        var repeated=request("GET");repeated.addParameter("limit","1","2");status(400,()->api.list(space,subject,repeated));
        var wrongQuery=request("GET");wrongQuery.addParameter("after",claim.toString());status(400,()->api.history(space,subject,claim,wrongQuery));
        var wrongCursor=request("GET");wrongCursor.addParameter("after",UUID.randomUUID().toString());status(400,()->api.list(space,subject,wrongCursor));
        var uppercase=request("GET");uppercase.addParameter("after","ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF");status(400,()->api.list(space,subject,uppercase));
        verifyNoInteractions(store);
    }

    @Test void invalidDomainIdsAndForeignAuthorityNeverReadCanonicalClaims() {
        status(400,()->api.get(space,UUID.randomUUID(),claim,request("GET")));status(400,()->api.get(space,subject,UUID.randomUUID(),request("GET")));
        status(400,()->api.create(UUID.randomUUID(),subject,edit("POST",null,body(predicate,object))));
        UUID foreign=ids.next();doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Space unavailable")).when(authority).authorize(eq(foreign),eq(token),eq(false),anyString());
        status(404,()->api.get(foreign,subject,claim,request("GET")));verifyNoInteractions(store);
    }

    @Test void duplicateStaleRetiredFailuresRemainActionableWithoutForeignDetails() {
        for(HttpStatus failure:List.of(HttpStatus.CONFLICT,HttpStatus.PRECONDITION_FAILED,HttpStatus.NOT_FOUND)) {
            doThrow(new ResponseStatusException(failure,"Claim unavailable")).when(store).change(space,subject,claim,actor,1,predicate,objectValue(),false,trace);
            status(failure.value(),()->api.update(space,subject,claim,edit("PUT","\"cl1\"",body(predicate,object))));
        }
    }

    @Test void retiredListingIsExplicitReadAuthorizedAndNoStore() {
        var request=request("GET");request.addParameter("state","RETIRED");request.addParameter("after",claim.toString());request.addParameter("limit","1");
        var page=new ClaimStore.Page(List.of(value(2,"RETIRED")),claim);when(store.retired(space,subject,claim,1)).thenReturn(page);
        var response=api.list(space,subject,request);assertThat(response.getBody()).isEqualTo(page);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var order=inOrder(authority,store);order.verify(authority).authorize(eq(space),eq(token),eq(false),anyString());order.verify(store).retired(space,subject,claim,1);
        var active=request("GET");active.addParameter("state","ACTIVE");api.list(space,subject,active);verify(store).list(space,subject,null,20);
    }
    @Test void retiredListingRejectsUnknownRepeatedStateAndHonorsAuthorityFailures() {
        for(String state:List.of("ALL","retired",""," RETIRED","RETIRED ")){var r=request("GET");r.addParameter("state",state);status(400,()->api.list(space,subject,r));}
        var repeated=request("GET");repeated.addParameter("state","ACTIVE","RETIRED");status(400,()->api.list(space,subject,repeated));
        for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){
            doThrow(new ResponseStatusException(failure)).when(authority).authorize(eq(space),any(),eq(false),anyString());
            var r=request("GET");r.addParameter("state","RETIRED");status(failure.value(),()->api.list(space,subject,r));
        }
        verifyNoInteractions(store);
    }

    @Test void literalObjectsAreStrictAndUseCanonicalExactStrings()throws Exception {
        for(var pair:List.of(new String[]{"STRING","text"},new String[]{"NUMBER","1.000"},new String[]{"DATE","2024-02-29"},new String[]{"BOOLEAN","false"})){
            var typed=ClaimStore.object(new ClaimStore.ObjectValue(pair[0],null,pair[1]));
            when(store.create(space,subject,actor,predicate,typed,trace)).thenReturn(value(1,"ACTIVE"));
            String json="{\"predicateConceptId\":\""+predicate+"\",\"object\":{\"type\":\""+pair[0]+"\",\"value\":\""+pair[1]+"\"}}";
            api.create(space,subject,edit("POST",null,json));verify(store).create(space,subject,actor,predicate,typed,trace);
        }
        clearInvocations(store);
        for(String object:List.of("{\"type\":\"NUMBER\",\"value\":1}","{\"type\":\"BOOLEAN\",\"value\":false}","{\"type\":\"DATE\",\"value\":\"2025-02-29\"}","{\"type\":\"NUMBER\",\"value\":\"1\",\"conceptId\":\""+predicate+"\"}"))
            status(400,()->api.create(space,subject,edit("POST",null,"{\"predicateConceptId\":\""+predicate+"\",\"object\":"+object+"}")));
        verifyNoInteractions(store);
    }
}
