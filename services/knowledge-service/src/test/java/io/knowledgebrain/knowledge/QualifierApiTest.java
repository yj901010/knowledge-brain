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

class QualifierApiTest {
    QualifierStore store;
    SpaceAuthorizationClient authority;
    QualifierController api;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,subject,claim,qualifier,source,actor;
    String token="a".repeat(43),trace="f".repeat(32),body,path;

    @BeforeEach void prepare() {
        space=ids.next();subject=ids.next();claim=ids.next();qualifier=ids.next();source=ids.next();actor=ids.next();
        path="/api/spaces/"+space+"/concepts/"+subject+"/claims/"+claim+"/qualifiers";
        body="{\"predicateConceptId\":\""+source+"\",\"value\":{\"type\":\"NUMBER\",\"value\":\"123.45\"},\"claimVersion\":1}";
        store=mock(QualifierStore.class);authority=mock(SpaceAuthorizationClient.class);
        var guard=new ConceptController(mock(ConceptStore.class),authority,new SimpleMeterRegistry(),"https://localhost:8443");
        api=new QualifierController(store,guard,new SimpleMeterRegistry());
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
    QualifierStore.Edit ref() {return new QualifierStore.Edit(source,new QualifierStore.Value("NUMBER","123.45"),1);}
    QualifierStore.Qualifier value(long version,String state) {
        Instant now=Instant.now();
        var c=new QualifierStore.ClaimSnapshot(ids.next(),1,subject,subject,new ClaimStore.ObjectValue("CONCEPT",subject));
        return new QualifierStore.Qualifier(qualifier,space,claim,subject,c,source,new QualifierStore.Value("NUMBER","123.45"),"PRIVATE",state,version,now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void retiredDiscoveryIsExplicitNoStoreAndReauthorizesEveryRead() {
        var r=request("GET");r.addParameter("state","RETIRED");r.addParameter("after",qualifier.toString());r.addParameter("limit","1");
        var archived=new QualifierStore.Page(List.of(value(2,"RETIRED")),null);when(store.retired(space,subject,claim,qualifier,1)).thenReturn(archived);
        var response=api.list(space,subject,claim,r);assertThat(response.getBody()).isEqualTo(archived);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var active=request("GET");active.addParameter("state","ACTIVE");when(store.list(space,subject,claim,null,20)).thenReturn(new QualifierStore.Page(List.of(),null));api.list(space,subject,claim,active);
        verify(authority,times(2)).authorize(eq(space),eq(token),eq(false),anyString());clearInvocations(store);
        when(authority.authorize(eq(space),eq(token),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.list(space,subject,claim,r));verifyNoInteractions(store);
    }
    @Test void lifecycleFilterIsStrictAndOnlyAcceptedOnQualifierCollectionReads() {
        for(String state:List.of("","ALL","retired"," RETIRED","RETIRED ")){var r=request("GET");r.addParameter("state",state);status(400,()->api.list(space,subject,claim,r));}
        var repeated=request("GET");repeated.addParameter("state","ACTIVE","RETIRED");status(400,()->api.list(space,subject,claim,repeated));
        var read=request("GET");read.addParameter("state","RETIRED");status(400,()->api.get(space,subject,claim,qualifier,read));status(400,()->api.history(space,subject,claim,qualifier,read));
        var create=edit("POST",null,body);create.addParameter("state","RETIRED");status(400,()->api.create(space,subject,claim,create));
        var update=edit("PUT","\"qf1\"",body);update.addParameter("state","RETIRED");status(400,()->api.update(space,subject,claim,qualifier,update));
        var remove=edit("DELETE","\"qf1\"","");remove.addParameter("state","RETIRED");status(400,()->api.retire(space,subject,claim,qualifier,remove));verifyNoInteractions(store);
    }
    @Test void creationUsesVerifiedActorExactReferenceIndependentEtagLocationAndNoStore() throws Exception {
        var r=edit("POST",null,body);r.addHeader("X-Principal-Id",ids.next().toString());
        when(store.create(space,subject,claim,actor,ref(),trace)).thenReturn(value(1,"ACTIVE"));
        var response=api.create(space,subject,claim,r);assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"qf1\"");assertThat(response.getHeaders().getLocation().toString()).isEqualTo(path+"/"+qualifier);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");
        verify(authority).authorize(eq(space),eq(token),eq(true),eq("00-"+trace+"-"+"a".repeat(16)+"-01"));
        verify(store).create(space,subject,claim,actor,ref(),trace);
    }

    @Test void getRepinAndRetireUseOnlyQualifierEtagAndRetireHasNoProposedReference() throws Exception {
        when(store.get(space,subject,claim,qualifier)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.get(space,subject,claim,qualifier,request("GET")).getHeaders().getETag()).isEqualTo("\"qf1\"");
        when(store.change(space,subject,claim,qualifier,actor,1,ref(),false,trace)).thenReturn(value(2,"ACTIVE"));
        var changed=api.update(space,subject,claim,qualifier,edit("PUT","\"qf1\"",body));
        assertThat(changed.getHeaders().getETag()).isEqualTo("\"qf2\"");assertThat(changed.getHeaders().getCacheControl()).isEqualTo("no-store");
        when(store.change(space,subject,claim,qualifier,actor,2,null,true,trace)).thenReturn(value(3,"RETIRED"));
        var retired=api.retire(space,subject,claim,qualifier,edit("DELETE","\"qf2\"",""));
        assertThat(retired.getStatusCode().value()).isEqualTo(204);assertThat(retired.getHeaders().getETag()).isEqualTo("\"qf3\"");
        assertThat(retired.getHeaders().getCacheControl()).isEqualTo("no-store");verify(store).change(space,subject,claim,qualifier,actor,2,null,true,trace);
    }

    @Test void missingWeakOtherAggregateWildcardRepeatedAndOverflowEtagsNeverMutate() {
        status(428,()->api.update(space,subject,claim,qualifier,edit("PUT",null,body)));
        status(428,()->api.retire(space,subject,claim,qualifier,edit("DELETE",null,"")));
        for(String tag:List.of("W/\"qf1\"","*","\"1\"","\"c1\"","\"a1\"","\"cl1\"","\"src1\"","\"qf0\"","\"qf01\"","qf1","\"qf1\", \"qf2\"","\"qf9223372036854775808\""))
            status(400,()->api.update(space,subject,claim,qualifier,edit("PUT",tag,body)));
        var repeated=edit("DELETE","\"qf1\"","");repeated.addHeader("If-Match","\"qf2\"");
        status(400,()->api.retire(space,subject,claim,qualifier,repeated));verifyNoInteractions(store);
    }

    @Test void retireRejectsAnyInputBodyWithoutTouchingQualifierEvenWithValidVersion() {
        for(String content:List.of(" ","{}",body,"null"))
            status(400,()->api.retire(space,subject,claim,qualifier,edit("DELETE","\"qf1\"",content)));
        verifyNoInteractions(store);
    }


    @Test void strictUnionJsonBoundariesAndAuthorityRejectBeforeMutation() {
        for(String content:List.of("{","[]","{}",body+" {}",body.replace("\"claimVersion\":1","\"claimVersion\":1,\"actorId\":\"spoof\""),body.replace("\"claimVersion\":1","\"claimVersion\":1,\"claimVersion\":1"),body.replace("\"123.45\"","123.45"),body.replace("NUMBER","UNSUPPORTED"),body.replace("123.45","1e1000")))status(400,()->api.create(space,subject,claim,edit("POST",null,content)));
        var large=edit("POST",null," ".repeat(16385));status(413,()->api.create(space,subject,claim,large));
        var wrong=edit("POST",null,body);wrong.setContentType("text/plain");status(415,()->api.create(space,subject,claim,wrong));
        var foreign=edit("POST",null,body);foreign.removeHeader("Origin");foreign.addHeader("Origin","https://evil.invalid");status(403,()->api.create(space,subject,claim,foreign));
        var denied=request("GET");when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        status(403,()->api.list(space,subject,claim,denied));verifyNoInteractions(store);
    }
    @Test void historyIsAuthorizedAndQueriesAreStrict() {
        when(store.history(space,subject,claim,qualifier,Long.MAX_VALUE,5)).thenReturn(List.of());
        assertThat(api.history(space,subject,claim,qualifier,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var invalid=request("GET");invalid.addParameter("limit","11");status(400,()->api.history(space,subject,claim,qualifier,invalid));
        var duplicate=request("GET");duplicate.addParameter("limit","1","2");status(400,()->api.list(space,subject,claim,duplicate));
    }
    @Test void typedValuesPreserveExactNumbersAndRejectAmbiguousDatesAndConceptIds() {
        assertThat(QualifierStore.value("NUMBER","9007199254740993.123456789012345678").value()).isEqualTo("9007199254740993.123456789012345678");
        assertThat(QualifierStore.value("NUMBER","-0.000").value()).isEqualTo("0");
        assertThat(QualifierStore.value("NUMBER","1.2300").value()).isEqualTo("1.23");
        assertThat(QualifierStore.value("STRING","  한글 <script>  ").value()).isEqualTo("한글 <script>");
        assertThat(QualifierStore.value("DATE","2024-02-29").value()).isEqualTo("2024-02-29");
        for(String v:List.of("2025-02-29","2024-13-01","0000-01-01","2024-2-01"))status(400,()->QualifierStore.value("DATE",v));
        for(String v:List.of("NaN","01","1e3","+1","0.0000000000000000001","9".repeat(39)))status(400,()->QualifierStore.value("NUMBER",v));
        status(400,()->QualifierStore.value("BOOLEAN","True"));status(400,()->QualifierStore.value("CONCEPT",UUID.randomUUID().toString()));
    }
}
