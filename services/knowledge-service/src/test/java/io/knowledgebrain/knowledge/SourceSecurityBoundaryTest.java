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
import org.springframework.http.*;
import org.springframework.http.MediaType;
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

class SourceSecurityBoundaryTest {
    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class,SourceController.class})
    static class TestWebConfiguration {
        @Bean SourceStore sourceStore() {return mock(SourceStore.class);}
        @Bean SpaceAuthorizationClient authority() {return mock(SpaceAuthorizationClient.class);}
        @Bean MeterRegistry metrics() {return new SimpleMeterRegistry();}
        @Bean ConceptController guard(SpaceAuthorizationClient authority,MeterRegistry metrics) {
            return new ConceptController(mock(ConceptStore.class),authority,metrics,"https://localhost:8443");
        }
    }

    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    SourceStore store;
    SpaceAuthorizationClient authority;
    UuidV7 ids=new UuidV7(Clock.systemUTC());
    UUID space,source,actor;
    String token="a".repeat(43),trace="f".repeat(32),path;
    String body="{\"title\":\"Filtered source\",\"url\":null,\"citation\":\"Author, Book, 2026\",\"licenseNote\":\"Manual note\"}";

    @BeforeEach void prepare() {
        space=ids.next();source=ids.next();actor=ids.next();path="/api/spaces/"+space+"/sources";start(true);
    }
    void start(boolean enabled) {
        if(context!=null)context.close();context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("source-test",Map.of("brain.knowledge.api.enabled",Boolean.toString(enabled),"brain.knowledge.public.enabled","false")));
        context.register(TestWebConfiguration.class);context.refresh();store=context.getBean(SourceStore.class);authority=context.getBean(SpaceAuthorizationClient.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    @AfterEach void stop() {if(context!=null)context.close();}
    MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder request) {
        return request.secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("Origin","https://localhost:8443")
            .header("Sec-Fetch-Site","same-origin").header("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");
    }
    SourceStore.Source value(long version,String state) {
        Instant now=Instant.now();return new SourceStore.Source(source,space,"Filtered source",null,"Author, Book, 2026","Manual note","PRIVATE",state,version,now,now);
    }

    @Test void enabledSourceCrudAndHistoryPassActualSecurityFiltersIntoCanonicalController() throws Exception {
        when(store.create(space,actor,"Filtered source",null,"Author, Book, 2026","Manual note",trace)).thenReturn(value(1,"ACTIVE"));
        mvc.perform(trusted(post(path)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
            .andExpect(header().string("ETag","\"src1\"")).andExpect(header().string("Location",path+"/"+source));
        when(store.list(space,null,20)).thenReturn(new SourceStore.Page(List.of(value(1,"ACTIVE")),null));
        mvc.perform(trusted(get(path))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        when(store.get(space,source)).thenReturn(value(1,"ACTIVE"));
        mvc.perform(trusted(get(path+"/"+source))).andExpect(status().isOk()).andExpect(header().string("ETag","\"src1\""));
        when(store.change(space,source,actor,1,"Filtered source",null,"Author, Book, 2026","Manual note",false,trace)).thenReturn(value(2,"ACTIVE"));
        mvc.perform(trusted(put(path+"/"+source)).header("If-Match","\"src1\"").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(header().string("ETag","\"src2\""));
        when(store.change(space,source,actor,2,null,null,null,null,true,trace)).thenReturn(value(3,"RETIRED"));
        mvc.perform(trusted(delete(path+"/"+source)).header("If-Match","\"src2\""))
            .andExpect(status().isNoContent()).andExpect(header().string("ETag","\"src3\""));
        when(store.history(space,source,Long.MAX_VALUE,5)).thenReturn(List.of());
        mvc.perform(trusted(get(path+"/"+source+"/revisions"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(store).create(space,actor,"Filtered source",null,"Author, Book, 2026","Manual note",trace);
        verify(store).list(space,null,20);verify(store).get(space,source);verify(store).history(space,source,Long.MAX_VALUE,5);
        verify(authority,times(3)).authorize(eq(space),eq(token),eq(true),anyString());
        verify(authority,times(3)).authorize(eq(space),eq(token),eq(false),anyString());
    }

    @Test void filterAllowanceStillRequiresHttpsSameOriginAndUniqueGuestCookie() throws Exception {
        mvc.perform(trusted(get(path)).secure(false)).andExpect(status().isBadRequest());
        mvc.perform(post(path).secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("Origin","https://evil.example")
            .header("Sec-Fetch-Site","same-origin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).secure(true).cookie(new Cookie("__Host-brain_guest",token)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("Origin","https://localhost:8443")
            .header("Sec-Fetch-Site","cross-site").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(trusted(get(path)).cookie(new Cookie("__Host-brain_guest","b".repeat(43)))).andExpect(status().isUnauthorized());
        verifyNoInteractions(store,authority);
    }

    @Test void enabledRoutesPropagateSpaceAuthorityDenialsWithoutReadingOrWritingSource() throws Exception {
        for(HttpStatus denied:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(denied,"Space authority denied")).when(authority).authorize(eq(space),eq(token),anyBoolean(),anyString());
            mvc.perform(trusted(get(path+"/"+source+"/revisions"))).andExpect(status().is(denied.value()));
            mvc.perform(trusted(post(path)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(denied.value()));
        }
        verifyNoInteractions(store);verify(authority,times(8)).authorize(eq(space),eq(token),anyBoolean(),anyString());
    }

    @Test void missingAndStaleVersionReachControllerPreconditionsAfterCsrfRouteAllowance() throws Exception {
        mvc.perform(trusted(put(path+"/"+source)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(428));
        mvc.perform(trusted(delete(path+"/"+source))).andExpect(status().is(428));verifyNoInteractions(store);
        doThrow(new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Source changed")).when(store)
            .change(space,source,actor,1,"Filtered source",null,"Author, Book, 2026","Manual note",false,trace);
        mvc.perform(trusted(put(path+"/"+source)).header("If-Match","\"src1\"").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionFailed());
        verify(store).change(space,source,actor,1,"Filtered source",null,"Author, Book, 2026","Manual note",false,trace);
    }

    @Test void disabledSourceCollectionItemsWritesAndHistoryAreDeniedByActualFilters() throws Exception {
        start(false);assertThat(context.getBeansOfType(SourceController.class)).isEmpty();
        for(var request:List.of(get(path),get(path+"/"+source),get(path+"/"+source+"/revisions"),
            post(path).contentType(MediaType.APPLICATION_JSON).content(body),put(path+"/"+source).contentType(MediaType.APPLICATION_JSON).content(body).header("If-Match","\"src1\""),
            delete(path+"/"+source).header("If-Match","\"src1\"")))mvc.perform(trusted(request)).andExpect(status().isForbidden());
        verifyNoInteractions(store,authority);
    }

    @Test void privateSourceRouteAllowanceDoesNotExposePublicInternalOrManagementSurfaces() throws Exception {
        for(String unrelated:List.of("/api/private","/actuator/env","/actuator/configprops","/actuator/metrics",
            "/api/public/spaces/"+space+"/sources","/api/public/spaces/"+space+"/concepts","/api/public/spaces/"+space+"/contributions",
            "/internal/knowledge/sources","/api/spaces/"+space+"/source-admin"))
            mvc.perform(trusted(get(unrelated))).andExpect(status().isForbidden());
        mvc.perform(trusted(post("/api/public/spaces/"+space+"/sources")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(store,authority);
    }
}
