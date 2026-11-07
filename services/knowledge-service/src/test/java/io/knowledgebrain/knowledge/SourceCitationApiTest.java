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

class SourceCitationApiTest {
    CitationStore store;
    SpaceAuthorizationClient authority;
    SourceCitationController api;
    SimpleMeterRegistry metrics;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,source,subject,claim,citation,actor;
    String token="a".repeat(43),trace="f".repeat(32),path;

    @BeforeEach void prepare() {
        space=ids.next();source=ids.next();subject=ids.next();claim=ids.next();citation=ids.next();actor=ids.next();
        path="/api/spaces/"+space+"/sources/"+source+"/citations";
        store=mock(CitationStore.class);authority=mock(SpaceAuthorizationClient.class);metrics=new SimpleMeterRegistry();
        var guard=new ConceptController(mock(ConceptStore.class),authority,metrics,"https://localhost:8443");
        api=new SourceCitationController(store,guard,metrics);
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    MockHttpServletRequest request() {
        var r=new MockHttpServletRequest("GET",path);r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest",token));
        r.addHeader("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");return r;
    }
    CitationStore.SourceCitation value() {
        Instant now=Instant.now();
        return new CitationStore.SourceCitation(citation,space,subject,"Current subject",claim,3,
            new CitationStore.ClaimSnapshot(ids.next(),1,subject,ids.next(),new ClaimStore.ObjectValue("CONCEPT",ids.next())),
            new CitationStore.SourcePin(source,ids.next(),2),4,"PRIVATE","ACTIVE",now,now);
    }
    void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class)
            .satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));
    }

    @Test void defaultReadRequiresFreshConceptReadAndReturnsUncachedSummaryWithoutEtagOrMutations() throws Exception {
        var item=value();var page=new CitationStore.SourcePage(List.of(item),null);
        when(store.bySource(space,source,null,20)).thenReturn(page);
        var r=request();r.addHeader("X-Principal-Id",ids.next().toString());r.addHeader("If-None-Match","\"cite3\"");
        var response=api.list(space,source,r);
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getBody()).isEqualTo(page);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getHeaders().getETag()).isNull();
        assertThat(response.getBody().items().getFirst().claim().version()).isEqualTo(1);
        assertThat(response.getBody().items().getFirst().currentClaimVersion()).isEqualTo(4);
        assertThat(metrics.get("brain.knowledge.citation.reads").tag("type","source").counter().count()).isEqualTo(1);
        verify(authority).authorize(space,token,false,"00-"+trace+"-"+"a".repeat(16)+"-01");
        verify(store).bySource(space,source,null,20);verifyNoMoreInteractions(store);
    }

    @Test void exclusiveUuidKeysetAndMaximumLimitArePassedExactly() throws Exception {
        var r=request();r.addParameter("after",citation.toString());r.addParameter("limit","100");
        var page=new CitationStore.SourcePage(List.of(value()),citation);when(store.bySource(space,source,citation,100)).thenReturn(page);
        assertThat(api.list(space,source,r).getBody()).isEqualTo(page);verify(store).bySource(space,source,citation,100);
        var one=request();one.addParameter("limit","1");when(store.bySource(space,source,null,1)).thenReturn(new CitationStore.SourcePage(List.of(),null));
        assertThat(api.list(space,source,one).getBody().items()).isEmpty();verify(store).bySource(space,source,null,1);
    }

    @Test void strictSingletonKnownQueryAndCanonicalPositiveNumericBoundsRejectBeforeStore() {
        for(String limit:List.of("0","101","-1","01","+1"," 1","1 ","1.0","1e0","2147483648","9223372036854775808","999999999999999999999999999","")) {
            var r=request();r.addParameter("limit",limit);status(400,()->api.list(space,source,r));
        }
        for(String name:List.of("before","sourceId","claimId","visibility","state","q","offset")) {
            var r=request();r.addParameter(name,"1");status(400,()->api.list(space,source,r));
        }
        for(String name:List.of("limit","after")) {var r=request();r.addParameter(name,"1","2");status(400,()->api.list(space,source,r));}
        for(String after:List.of(UUID.randomUUID().toString(),"ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF","invalid","1-1-1-1-1"," "+citation,citation+" ","")) {
            var r=request();r.addParameter("after",after);status(400,()->api.list(space,source,r));
        }
        verifyNoInteractions(store);
    }

    @Test void nonUuidV7SpaceAndSourceIdentitiesAreRejectedBeforeStore() {
        status(400,()->api.list(UUID.randomUUID(),source,request()));status(400,()->api.list(space,UUID.randomUUID(),request()));
        status(400,()->api.list(space,UUID.fromString("00000000-0000-7000-0000-000000000001"),request()));verifyNoInteractions(store);
    }

    @Test void anyNonemptyGetBodyIsRejectedEvenWhitespaceOrJsonNull() {
        for(String content:List.of(" ","{}","null","{\"sourceId\":\""+source+"\"}")) {
            var r=request();r.setContent(content.getBytes(StandardCharsets.UTF_8));r.setContentType("application/json");
            status(400,()->api.list(space,source,r));
        }
        var large=request();large.setContent(new byte[32769]);status(400,()->api.list(space,source,large));verifyNoInteractions(store);
    }

    @Test void realHttpsUniqueIdentityAndFreshAuthorityDenialsRemainRequiredForSourceReads() {
        var insecure=request();insecure.setSecure(false);insecure.addHeader("X-Forwarded-Proto","https");status(400,()->api.list(space,source,insecure));
        var duplicate=request();duplicate.setCookies(new Cookie("__Host-brain_guest",token),new Cookie("__Host-brain_guest","b".repeat(43)));
        status(401,()->api.list(space,source,duplicate));
        for(HttpStatus denied:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(denied,"Space authority denied")).when(authority).authorize(eq(space),any(),anyBoolean(),anyString());
            status(denied.value(),()->api.list(space,source,request()));
        }
        verifyNoInteractions(store);
    }

    @Test void unknownForeignSourcesAndStoreUnavailabilityKeepTheirStatusWithoutInventingEmptyRows() {
        for(HttpStatus failure:List.of(HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(failure,"Source discovery unavailable")).when(store).bySource(space,source,null,20);
            status(failure.value(),()->api.list(space,source,request()));
        }
        assertThat(metrics.find("brain.knowledge.citation.reads").tag("type","source").counter()).isNull();
    }

    @Test void knownSourceWithNoEffectiveCitationsReturnsEmptyPageRatherThanNotFound() throws Exception {
        when(store.bySource(space,source,null,20)).thenReturn(new CitationStore.SourcePage(List.of(),null));
        var response=api.list(space,source,request());assertThat(response.getBody().items()).isEmpty();assertThat(response.getBody().nextAfter()).isNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");assertThat(response.getHeaders().getETag()).isNull();
    }
}
