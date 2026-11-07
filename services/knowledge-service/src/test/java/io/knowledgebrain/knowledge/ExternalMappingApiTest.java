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

class ExternalMappingApiTest {
    ExternalMappingStore store;
    SpaceAuthorizationClient authority;
    ExternalMappingController api;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,concept,mapping,source,actor;
    String token="a".repeat(43),trace="f".repeat(32),body,path;

    @BeforeEach void prepare() {
        space=ids.next();concept=ids.next();mapping=ids.next();source=ids.next();actor=ids.next();
        path="/api/spaces/"+space+"/concepts/"+concept+"/external-mappings";
        body="{\"system\":\"Example system\",\"code\":\"CODE-1\",\"systemVersion\":\"2026\",\"label\":\"Example label\",\"sourceId\":\""+source+"\",\"sourceVersion\":1,\"conceptVersion\":1}";
        store=mock(ExternalMappingStore.class);authority=mock(SpaceAuthorizationClient.class);
        var guard=new ConceptController(mock(ConceptStore.class),authority,new SimpleMeterRegistry(),"https://localhost:8443");
        api=new ExternalMappingController(store,guard,new SimpleMeterRegistry());
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
    ExternalMappingStore.Edit ref() {return new ExternalMappingStore.Edit("Example system","CODE-1","2026","Example label",source,1,1);}
    ExternalMappingStore.Mapping value(long version,String state) {
        Instant now=Instant.now();
        var c=new ExternalMappingStore.ConceptSnapshot(ids.next(),1,"Concept","Original description");
        var ref=new ExternalMappingStore.SourceSnapshot(source,ids.next(),1,"Source",null,"Book","Manual license note");
        return new ExternalMappingStore.Mapping(mapping,space,concept,c,"Example system","CODE-1","2026","Example label",ref,"CANDIDATE","PRIVATE",state,version,now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void creationUsesVerifiedActorExactReferenceIndependentEtagLocationAndNoStore() throws Exception {
        var r=edit("POST",null,body);r.addHeader("X-Principal-Id",ids.next().toString());
        when(store.create(space,concept,actor,ref(),trace)).thenReturn(value(1,"ACTIVE"));
        var response=api.create(space,concept,r);assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getETag()).isEqualTo("\"xm1\"");assertThat(response.getHeaders().getLocation().toString()).isEqualTo(path+"/"+mapping);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getBody().visibility()).isEqualTo("PRIVATE");
        verify(authority).authorize(eq(space),eq(token),eq(true),eq("00-"+trace+"-"+"a".repeat(16)+"-01"));
        verify(store).create(space,concept,actor,ref(),trace);
    }

    @Test void getRepinAndRetireUseOnlyQualifierEtagAndRetireHasNoProposedReference() throws Exception {
        when(store.get(space,concept,mapping)).thenReturn(value(1,"ACTIVE"));
        assertThat(api.get(space,concept,mapping,request("GET")).getHeaders().getETag()).isEqualTo("\"xm1\"");
        when(store.change(space,concept,mapping,actor,1,ref(),false,trace)).thenReturn(value(2,"ACTIVE"));
        var changed=api.update(space,concept,mapping,edit("PUT","\"xm1\"",body));
        assertThat(changed.getHeaders().getETag()).isEqualTo("\"xm2\"");assertThat(changed.getHeaders().getCacheControl()).isEqualTo("no-store");
        when(store.change(space,concept,mapping,actor,2,null,true,trace)).thenReturn(value(3,"RETIRED"));
        var retired=api.retire(space,concept,mapping,edit("DELETE","\"xm2\"",""));
        assertThat(retired.getStatusCode().value()).isEqualTo(204);assertThat(retired.getHeaders().getETag()).isEqualTo("\"xm3\"");
        assertThat(retired.getHeaders().getCacheControl()).isEqualTo("no-store");verify(store).change(space,concept,mapping,actor,2,null,true,trace);
    }

    @Test void missingWeakOtherAggregateWildcardRepeatedAndOverflowEtagsNeverMutate() {
        status(428,()->api.update(space,concept,mapping,edit("PUT",null,body)));
        status(428,()->api.retire(space,concept,mapping,edit("DELETE",null,"")));
        for(String tag:List.of("W/\"xm1\"","*","\"1\"","\"c1\"","\"a1\"","\"cl1\"","\"src1\"","\"xm0\"","\"xm01\"","xm1","\"xm1\", \"xm2\"","\"xm9223372036854775808\""))
            status(400,()->api.update(space,concept,mapping,edit("PUT",tag,body)));
        var repeated=edit("DELETE","\"xm1\"","");repeated.addHeader("If-Match","\"xm2\"");
        status(400,()->api.retire(space,concept,mapping,repeated));verifyNoInteractions(store);
    }

    @Test void retireRejectsAnyInputBodyWithoutTouchingQualifierEvenWithValidVersion() {
        for(String content:List.of(" ","{}",body,"null"))
            status(400,()->api.retire(space,concept,mapping,edit("DELETE","\"xm1\"",content)));
        verifyNoInteractions(store);
    }



    @Test void exactJsonAndAuthorityCannotPromoteOrPublishMapping() {
        for(String content:List.of("{","[]","{}",body+" {}",body.replace("\"conceptVersion\":1","\"conceptVersion\":1,\"mappingState\":\"OFFICIAL\""),body.replace("\"conceptVersion\":1","\"conceptVersion\":1,\"conceptVersion\":1"),body.replace("\"sourceVersion\":1","\"sourceVersion\":\"1\""),body.replace("\"CODE-1\"","null"),body.replace("\"2026\"","2026")))status(400,()->api.create(space,concept,edit("POST",null,content)));
        var big=edit("POST",null," ".repeat(16385));status(413,()->api.create(space,concept,big));
        var mime=edit("POST",null,body);mime.setContentType("text/plain");status(415,()->api.create(space,concept,mime));
        var foreign=edit("POST",null,body);foreign.removeHeader("Origin");foreign.addHeader("Origin","https://evil.invalid");status(403,()->api.create(space,concept,foreign));
        var denied=request("GET");when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.list(space,concept,denied));verifyNoInteractions(store);
    }
    @Test void historyAndEveryReadRequireAuthorityAndStrictCursors() {
        when(store.history(space,concept,mapping,Long.MAX_VALUE,5)).thenReturn(List.of());
        assertThat(api.history(space,concept,mapping,request("GET")).getHeaders().getCacheControl()).isEqualTo("no-store");
        var invalid=request("GET");invalid.addParameter("limit","11");status(400,()->api.history(space,concept,mapping,invalid));
        var duplicate=request("GET");duplicate.addParameter("limit","1","2");status(400,()->api.list(space,concept,duplicate));
    }
    @Test void mappingIdentifiersAreTrimmedCaseSensitiveAndBounded() {
        assertThat(ExternalMappingStore.edit(" Case-Sensitive "," CODE "," ","",source,1,1)).isEqualTo(new ExternalMappingStore.Edit("Case-Sensitive","CODE","","",source,1,1));
        for(String system:List.of(" ","x".repeat(101),"\uD800","a\nb","a\0b"))status(400,()->ExternalMappingStore.edit(system,"code","","",source,1,1));
        status(400,()->ExternalMappingStore.edit("system","x".repeat(201),"","",source,1,1));
        status(400,()->ExternalMappingStore.edit("system","code","x".repeat(101),"",source,1,1));
        status(400,()->ExternalMappingStore.edit("system","code","","",source,0,1));
    }

    @Test void retiredCollectionIsExplicitNoStoreAndRechecksAuthority() {
        var row=value(2,"RETIRED");var query=request("GET");query.addParameter("state","RETIRED");query.addParameter("after",mapping.toString());query.addParameter("limit","1");
        when(store.retired(space,concept,mapping,1)).thenReturn(new ExternalMappingStore.Page(List.of(row),mapping));
        var response=api.list(space,concept,query);assertThat(response.getBody().items()).containsExactly(row);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        var active=request("GET");active.addParameter("state","ACTIVE");when(store.list(space,concept,null,20)).thenReturn(new ExternalMappingStore.Page(List.of(),null));api.list(space,concept,active);api.list(space,concept,request("GET"));verify(store,times(2)).list(space,concept,null,20);
        clearInvocations(store);for(HttpStatus failure:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure)).when(authority).authorize(eq(space),eq(token),eq(false),anyString());status(failure.value(),()->api.list(space,concept,query));
        }verifyNoInteractions(store);
    }
    @Test void stateFilterCannotBeDuplicatedOrUsedOnOtherOperations() {
        for(String state:List.of("","ALL","retired"," RETIRED","RETIRED ")){var q=request("GET");q.addParameter("state",state);status(400,()->api.list(space,concept,q));}
        var repeated=request("GET");repeated.addParameter("state","RETIRED","ACTIVE");status(400,()->api.list(space,concept,repeated));
        var get=request("GET");get.addParameter("state","RETIRED");status(400,()->api.get(space,concept,mapping,get));status(400,()->api.history(space,concept,mapping,get));
        var post=edit("POST",null,body);post.addParameter("state","RETIRED");status(400,()->api.create(space,concept,post));
        var put=edit("PUT","\"xm1\"",body);put.addParameter("state","RETIRED");status(400,()->api.update(space,concept,mapping,put));
        var delete=edit("DELETE","\"xm1\"","");delete.addParameter("state","RETIRED");status(400,()->api.retire(space,concept,mapping,delete));verifyNoInteractions(store);
    }
}
