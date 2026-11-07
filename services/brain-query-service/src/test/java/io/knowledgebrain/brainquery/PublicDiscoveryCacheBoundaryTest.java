package io.knowledgebrain.brainquery;

import java.util.*;
import jakarta.servlet.http.Cookie;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicDiscoveryCacheBoundaryTest {
    final UUID space = UUID.fromString(PublicProjectionTest.SPACE), id = UUID.fromString(PublicProjectionTest.ID);
    final PublicProjectionConsumer consumer = mock(PublicProjectionConsumer.class);
    final NoteSearchClient search = mock(NoteSearchClient.class);
    final SpaceAuthorizationClient authority = mock(SpaceAuthorizationClient.class);
    final PublicCanonicalClient canonical = mock(PublicCanonicalClient.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final PublicSearchCacheTest.Memory transport = new PublicSearchCacheTest.Memory();
    MockHttpServletRequest request() {
        var r = new MockHttpServletRequest("GET", "/api/public/spaces/"+space+"/search");
        r.setSecure(true); r.setQueryString("q=public"); r.setParameter("q", "public");
        r.setCookies(new Cookie("__Host-brain_guest", "a".repeat(43))); return r;
    }
    PublicCanonicalClient.Item item(long version, String state, boolean current) {
        return new PublicCanonicalClient.Item(id, "CONCEPT", "Fresh canonical title", version, state,
                PublicProjection.JSON.createObjectNode(), PublicProjection.JSON.createObjectNode(), current, "", false);
    }
    PublicSearchCache warm() {
        transport.value = PublicSearchCache.encode(new PublicSearchCache.Query(space, "public", null, 0, 20),
                new PublicSearchCache.Page(1, List.of(new PublicSearchCache.Candidate(id, "CONCEPT", 1))), System.currentTimeMillis());
        when(consumer.isReady()).thenReturn(true);
        return new PublicSearchCache(transport, metrics);
    }
    @Test void hotCacheStillChecksCurrentAuthorityAndCanonicalVersionOnEveryRequest() {
        try (var cache = warm()) {
            var controller = new PublicDiscoveryController(consumer, search, authority, canonical, metrics, cache);
            when(canonical.check(eq(space), eq(List.of(id)), anyString())).thenReturn(Map.of(id, item(1,"ACTIVE",true)))
                    .thenReturn(Map.of(id, item(2,"ACTIVE",true))).thenReturn(Map.of(id,item(1,"RETIRED",true)))
                    .thenReturn(Map.of(id,item(1,"ACTIVE",false))).thenReturn(Map.of());
            var first = controller.search(space.toString(), request());
            assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(first.getBody().items()).extracting(PublicCanonicalClient.Item::title).containsExactly("Fresh canonical title");
            for(int i=0;i<4;i++) {
                var response = controller.search(space.toString(), request()).getBody();
                assertThat(response.items()).isEmpty(); assertThat(response.staleOmitted()).isEqualTo(1);
            }
            verify(authority,times(5)).authorize(eq(space),eq("a".repeat(43)),anyString(),eq("PUBLIC_SEARCH"));
            verify(canonical,times(5)).check(eq(space),eq(List.of(id)),anyString()); verifyNoInteractions(search);
        }
    }
    @Test void revocationAndRebuildGuardRunBeforeCacheAndCanonicalFailureStillFailsClosed() {
        try (var cache = warm()) {
            var controller = new PublicDiscoveryController(consumer, search, authority, canonical, metrics, cache);
            doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(authority).authorize(any(),any(),any(),any());
            assertThatThrownBy(()->controller.search(space.toString(),request())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
            assertThat(transport.reads).hasValue(0); verifyNoInteractions(canonical,search);
            reset(authority); when(consumer.isReady()).thenReturn(false);
            assertThatThrownBy(()->controller.search(space.toString(),request())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
            assertThat(transport.reads).hasValue(0);
            when(consumer.isReady()).thenReturn(true); when(canonical.check(any(),any(),any())).thenThrow(new NoteSearchClient.Unavailable());
            assertThatThrownBy(()->controller.search(space.toString(),request())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
            verifyNoInteractions(search);
        }
    }
    @Test void readinessLossDuringHotCacheRequestCannotReturnResults() {
        try(var cache=warm()) {
            var controller=new PublicDiscoveryController(consumer,search,authority,canonical,metrics,cache);
            when(consumer.isReady()).thenReturn(true,false); when(canonical.check(any(),any(),any())).thenReturn(Map.of(id,item(1,"ACTIVE",true)));
            assertThatThrownBy(()->controller.search(space.toString(),request())).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
            verify(canonical).check(eq(space),eq(List.of(id)),anyString()); verifyNoInteractions(search);
        }
    }
}
