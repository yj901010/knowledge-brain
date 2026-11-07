package io.knowledgebrain.knowledge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SourceCitationSecurityBoundaryTest {
    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class,SourceCitationController.class})
    static class TestWebConfiguration {
        @Bean CitationStore citationStore() {return mock(CitationStore.class);}
        @Bean SpaceAuthorizationClient authority() {return mock(SpaceAuthorizationClient.class);}
        @Bean MeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean ConceptController guard(SpaceAuthorizationClient authority,MeterRegistry metrics) {
            return new ConceptController(mock(ConceptStore.class),authority,metrics,"https://localhost:8443");
        }
    }
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    CitationStore store;
    SpaceAuthorizationClient authority;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,source,subject,claim,citation,actor;
    String token="a".repeat(43),trace="f".repeat(32),path;

    @BeforeEach void prepare() {
        space=ids.next();source=ids.next();subject=ids.next();claim=ids.next();citation=ids.next();actor=ids.next();
        path="/api/spaces/"+space+"/sources/"+source+"/citations";start(true);
    }
    void start(boolean enabled) {
        if(context!=null)context.close();context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("source-citation-test",Map.of("brain.knowledge.api.enabled",Boolean.toString(enabled),"brain.knowledge.public.enabled","false")));
        context.register(TestWebConfiguration.class);context.refresh();store=context.getBean(CitationStore.class);authority=context.getBean(SpaceAuthorizationClient.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    @AfterEach void stop() {if(context!=null)context.close();}
    MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder r) {
        return r.secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");
    }
    CitationStore.SourceCitation value() {
        Instant now=Instant.now();return new CitationStore.SourceCitation(citation,space,subject,"Current subject",claim,3,
            new CitationStore.ClaimSnapshot(ids.next(),1,subject,ids.next(),new ClaimStore.ObjectValue("CONCEPT",ids.next())),
            new CitationStore.SourcePin(source,ids.next(),2),4,"PRIVATE","ACTIVE",now,now);
    }

    @Test void sourceReadAllowancePassesActualProductionFilterToFreshReadGuardAndSerializesSeparatePins() throws Exception {
        var item=value();when(store.bySource(space,source,null,20)).thenReturn(new CitationStore.SourcePage(List.of(item),null));
        mvc.perform(trusted(get(path)).header("If-None-Match","\"cite3\"")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.items[0].citationId").value(citation.toString()))
            .andExpect(jsonPath("$.items[0].subjectTitle").value("Current subject"))
            .andExpect(jsonPath("$.items[0].version").value(3)).andExpect(jsonPath("$.items[0].claim.version").value(1))
            .andExpect(jsonPath("$.items[0].source.version").value(2)).andExpect(jsonPath("$.items[0].currentClaimVersion").value(4))
            .andExpect(jsonPath("$.items[0].source.sourceId").value(source.toString())).andExpect(jsonPath("$.items[0].source.title").doesNotExist())
            .andExpect(jsonPath("$.items[0].source.citation").doesNotExist()).andExpect(jsonPath("$.items[0].source.licenseNote").doesNotExist());
        verify(authority).authorize(space,token,false,"00-"+trace+"-"+"a".repeat(16)+"-01");verify(store).bySource(space,source,null,20);
        verifyNoMoreInteractions(store);
    }

    @Test void realServletStrictQueriesNonemptyGetBodiesAndNonUuidV7PathNeverReachStore() throws Exception {
        for(String content:List.of(" ","{}","null"))mvc.perform(trusted(get(path)).content(content)).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).param("limit","1","2")).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).param("limit","01")).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).param("limit","101")).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).param("after",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).param("after","ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF")).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).param("before","1")).andExpect(status().isBadRequest());
        for(String invalid:List.of("ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF","1-1-7000-8000-1",UUID.randomUUID().toString(),"not-an-id")) {
            mvc.perform(trusted(get(path.replace(source.toString(),invalid)))).andExpect(status().isBadRequest());
            mvc.perform(trusted(get(path.replace(space.toString(),invalid)))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(store);
    }

    @Test void verifiedHttpsUniqueCookieAndFreshAuthorityDenialsFailClosedThroughRealFilters() throws Exception {
        mvc.perform(trusted(get(path)).secure(false).header("X-Forwarded-Proto","https")).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).cookie(new Cookie("__Host-brain_guest","b".repeat(43)))).andExpect(status().isUnauthorized());
        verifyNoInteractions(store,authority);
        for(HttpStatus denied:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(denied,"Fresh Space authorization denied")).when(authority).authorize(eq(space),eq(token),anyBoolean(),anyString());
            mvc.perform(trusted(get(path))).andExpect(status().is(denied.value()));
        }
        verifyNoInteractions(store);verify(authority,times(4)).authorize(eq(space),eq(token),eq(false),anyString());
    }

    @Test void unknownForeignPublicSpaceAndDatabaseUnavailabilityPreserve404Or503ThroughFilters() throws Exception {
        for(HttpStatus unavailable:List.of(HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(unavailable,"Source discovery unavailable")).when(store).bySource(space,source,null,20);
            mvc.perform(trusted(get(path))).andExpect(status().is(unavailable.value()));
        }
        UUID foreignSpace=ids.next();String foreignPath=path.replace(space.toString(),foreignSpace.toString());
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"Foreign or Public Space is unavailable")).when(authority).authorize(eq(foreignSpace),eq(token),anyBoolean(),anyString());
        mvc.perform(trusted(get(foreignPath))).andExpect(status().isNotFound());
        verify(store,times(2)).bySource(space,source,null,20);verifyNoMoreInteractions(store);
    }

    @Test void disabledApiDeniesSourceDiscoveryBeforeAuthorityAndHasNoControllerBean() throws Exception {
        start(false);assertThat(context.getBeansOfType(SourceCitationController.class)).isEmpty();
        mvc.perform(trusted(get(path))).andExpect(status().isForbidden());verifyNoInteractions(store,authority);
    }

    @Test void privateSourceReadAllowanceCannotCreateWritePublicInternalOrManagementEndpoint() throws Exception {
        for(String denied:List.of("/api/private","/actuator/env","/actuator/configprops","/actuator/metrics",
            path.replace("/api/spaces/","/api/public/spaces/"),"/internal/knowledge/source-citations","/api/spaces/"+space+"/citation-admin"))
            mvc.perform(trusted(get(denied))).andExpect(status().isForbidden());
        for(var write:List.of(post(path),put(path),delete(path)))mvc.perform(trusted(write)).andExpect(status().isMethodNotAllowed());
        mvc.perform(trusted(get(path+"/"+citation))).andExpect(status().isNotFound());
        mvc.perform(trusted(get(path+"/revisions"))).andExpect(status().isNotFound());verifyNoInteractions(store,authority);
    }
}
