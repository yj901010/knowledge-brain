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

class CitationSecurityBoundaryTest {
    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfiguration.class,CitationController.class})
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
    UUID space,subject,claim,citation,source,actor;
    String token="a".repeat(43),trace="f".repeat(32),path,body;

    @BeforeEach void prepare() {
        space=ids.next();subject=ids.next();claim=ids.next();citation=ids.next();source=ids.next();actor=ids.next();
        path="/api/spaces/"+space+"/concepts/"+subject+"/claims/"+claim+"/citations";
        body="{\"sourceId\":\""+source+"\",\"sourceVersion\":1,\"claimVersion\":1}";start(true);
    }
    void start(boolean enabled) {
        if(context!=null)context.close();context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("citation-test",Map.of("brain.knowledge.api.enabled",Boolean.toString(enabled),"brain.knowledge.public.enabled","false")));
        context.register(TestWebConfiguration.class);context.refresh();store=context.getBean(CitationStore.class);authority=context.getBean(SpaceAuthorizationClient.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    @AfterEach void stop() {if(context!=null)context.close();}
    MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder request) {
        return request.secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("Origin","https://localhost:8443")
            .header("Sec-Fetch-Site","same-origin").header("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");
    }
    CitationStore.Reference ref() {return new CitationStore.Reference(source,1,1);}
    CitationStore.Citation value(long version,String state) {
        Instant now=Instant.now();
        return new CitationStore.Citation(citation,space,claim,subject,
            new CitationStore.ClaimSnapshot(ids.next(),1,subject,subject,new ClaimStore.ObjectValue("CONCEPT",subject)),
            new CitationStore.SourceSnapshot(source,ids.next(),1,"Pinned source",null,"Book, 2026","Manual license"),"PRIVATE",state,version,now,now);
    }

    @Test void allSixCitationRoutesPassActualSecurityFiltersAndCsrfExemptionsIntoGuardedController() throws Exception {
        when(store.create(space,subject,claim,actor,ref(),trace)).thenReturn(value(1,"ACTIVE"));
        mvc.perform(trusted(post(path)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
            .andExpect(header().string("ETag","\"cite1\"")).andExpect(header().string("Location",path+"/"+citation));
        when(store.list(space,subject,claim,null,20)).thenReturn(new CitationStore.Page(List.of(value(1,"ACTIVE")),null));
        mvc.perform(trusted(get(path))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        when(store.get(space,subject,claim,citation)).thenReturn(value(1,"ACTIVE"));
        mvc.perform(trusted(get(path+"/"+citation))).andExpect(status().isOk()).andExpect(header().string("ETag","\"cite1\""));
        when(store.change(space,subject,claim,citation,actor,1,ref(),false,trace)).thenReturn(value(2,"ACTIVE"));
        mvc.perform(trusted(put(path+"/"+citation)).header("If-Match","\"cite1\"").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(header().string("ETag","\"cite2\""));
        when(store.change(space,subject,claim,citation,actor,2,null,true,trace)).thenReturn(value(3,"RETIRED"));
        mvc.perform(trusted(delete(path+"/"+citation)).header("If-Match","\"cite2\""))
            .andExpect(status().isNoContent()).andExpect(header().string("ETag","\"cite3\""));
        when(store.history(space,subject,claim,citation,Long.MAX_VALUE,5)).thenReturn(List.of());
        mvc.perform(trusted(get(path+"/"+citation+"/revisions"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(store).create(space,subject,claim,actor,ref(),trace);verify(store).list(space,subject,claim,null,20);
        verify(store).get(space,subject,claim,citation);verify(store).history(space,subject,claim,citation,Long.MAX_VALUE,5);
        verify(authority,times(3)).authorize(eq(space),eq(token),eq(true),anyString());verify(authority,times(3)).authorize(eq(space),eq(token),eq(false),anyString());
    }

    @Test void allowedRoutesStillRejectForgedHttpsForeignOrMissingOriginCrossSiteAndDuplicateIdentity() throws Exception {
        mvc.perform(trusted(get(path)).secure(false).header("X-Forwarded-Proto","https")).andExpect(status().isBadRequest());
        mvc.perform(post(path).secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("Origin","https://evil.example")
            .header("Sec-Fetch-Site","same-origin").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).secure(true).cookie(new Cookie("__Host-brain_guest",token)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("Origin","https://localhost:8443")
            .header("Sec-Fetch-Site","cross-site").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(trusted(get(path+"/"+citation+"/revisions")).cookie(new Cookie("__Host-brain_guest","b".repeat(43)))).andExpect(status().isUnauthorized());
        verifyNoInteractions(store,authority);
    }

    @Test void retainedHistoryAndEveryMutationPropagateFreshAuthorityDenials() throws Exception {
        for(HttpStatus denied:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)) {
            doThrow(new ResponseStatusException(denied,"Authority denied")).when(authority).authorize(eq(space),eq(token),anyBoolean(),anyString());
            mvc.perform(trusted(get(path+"/"+citation+"/revisions"))).andExpect(status().is(denied.value()));
            mvc.perform(trusted(post(path)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(denied.value()));
            mvc.perform(trusted(put(path+"/"+citation)).header("If-Match","\"cite1\"").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(denied.value()));
            mvc.perform(trusted(delete(path+"/"+citation)).header("If-Match","\"cite1\"")).andExpect(status().is(denied.value()));
        }
        verifyNoInteractions(store);verify(authority,times(16)).authorize(eq(space),eq(token),anyBoolean(),anyString());
    }

    @Test void strictPreconditionsSurviveRealServletAndFilterRouting() throws Exception {
        mvc.perform(trusted(put(path+"/"+citation)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is(428));
        mvc.perform(trusted(delete(path+"/"+citation))).andExpect(status().is(428));verifyNoInteractions(store);
        doThrow(new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Pinned context changed")).when(store)
            .change(space,subject,claim,citation,actor,1,ref(),false,trace);
        mvc.perform(trusted(put(path+"/"+citation)).header("If-Match","\"cite1\"").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionFailed());
        mvc.perform(trusted(post(path)).contentType(MediaType.APPLICATION_JSON).content(body.replace("\"claimVersion\":1","\"claimVersion\":1.0"))).andExpect(status().isBadRequest());
        verify(store).change(space,subject,claim,citation,actor,1,ref(),false,trace);
    }

    @Test void disabledApiDeniesCollectionItemWritesAndRetainedHistoryBeforeAuthority() throws Exception {
        start(false);assertThat(context.getBeansOfType(CitationController.class)).isEmpty();
        for(var r:List.of(get(path),get(path+"/"+citation),get(path+"/"+citation+"/revisions"),
            post(path).contentType(MediaType.APPLICATION_JSON).content(body),put(path+"/"+citation).contentType(MediaType.APPLICATION_JSON).content(body).header("If-Match","\"cite1\""),
            delete(path+"/"+citation).header("If-Match","\"cite1\"")))mvc.perform(trusted(r)).andExpect(status().isForbidden());
        verifyNoInteractions(store,authority);
    }

    @Test void privateCitationAllowanceNeverOpensPublicOrInternalOrManagementRoutes() throws Exception {
        for(String denied:List.of("/api/private","/actuator/env","/actuator/configprops","/actuator/metrics",
            path.replace("/api/spaces/","/api/public/spaces/"),"/internal/knowledge/citations","/api/spaces/"+space+"/citation-admin"))
            mvc.perform(trusted(get(denied))).andExpect(status().isForbidden());
        mvc.perform(trusted(post(path.replace("/api/spaces/","/api/public/spaces/"))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(store,authority);
    }
}
