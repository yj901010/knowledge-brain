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

class ClaimUsageApiTest {
    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class,ClaimUsageController.class})
    static class Web {
        @Bean ClaimStore store(){return mock(ClaimStore.class);}
        @Bean SpaceAuthorizationClient authority(){return mock(SpaceAuthorizationClient.class);}
        @Bean MeterRegistry metrics(){return new SimpleMeterRegistry();}
        @Bean ConceptController guard(SpaceAuthorizationClient authority,MeterRegistry metrics){return new ConceptController(mock(ConceptStore.class),authority,metrics,"https://localhost:8443");}
    }
    AnnotationConfigWebApplicationContext context;MockMvc mvc;ClaimStore store;SpaceAuthorizationClient authority;
    UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,concept,subject,claim,actor;
    String token="a".repeat(43),trace="f".repeat(32),path;
    @BeforeEach void prepare(){space=ids.next();concept=ids.next();subject=ids.next();claim=ids.next();actor=ids.next();path="/api/spaces/"+space+"/concepts/"+concept+"/claim-usages";start(true);}
    void start(boolean enabled){
        if(context!=null)context.close();context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("usage-test",Map.of("brain.knowledge.api.enabled",Boolean.toString(enabled),"brain.knowledge.public.enabled","false")));
        context.register(Web.class);context.refresh();store=context.getBean(ClaimStore.class);authority=context.getBean(SpaceAuthorizationClient.class);
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
        when(authority.authorize(eq(space),eq(token),anyBoolean(),anyString())).thenReturn(actor);
    }
    @AfterEach void stop(){if(context!=null)context.close();}
    MockHttpServletRequestBuilder trusted(MockHttpServletRequestBuilder r){return r.secure(true).cookie(new Cookie("__Host-brain_guest",token)).header("traceparent","00-"+trace+"-"+"a".repeat(16)+"-01");}
    ClaimStore.Usage value(){Instant now=Instant.now();return new ClaimStore.Usage(new ClaimStore.Claim(claim,space,subject,ids.next(),new ClaimStore.ObjectValue("CONCEPT",concept),"PRIVATE","ACTIVE",3,"DRAFT",now,now),"Current subject","Current predicate","Current object");}

    @Test void defaultObjectReadUsesFreshAuthorityAndCurrentTitlesWithoutCollectionEtag()throws Exception{
        when(store.usages(space,concept,ClaimStore.UsageRole.OBJECT,null,20)).thenReturn(new ClaimStore.UsagePage(List.of(value()),null));
        mvc.perform(trusted(get(path)).header("X-Principal-Id",ids.next()).header("If-None-Match","\"cl3\""))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(header().doesNotExist("ETag"))
            .andExpect(jsonPath("$.items[0].claim.claimId").value(claim.toString())).andExpect(jsonPath("$.items[0].claim.version").value(3))
            .andExpect(jsonPath("$.items[0].subjectTitle").value("Current subject")).andExpect(jsonPath("$.items[0].predicateTitle").value("Current predicate"))
            .andExpect(jsonPath("$.items[0].objectTitle").value("Current object")).andExpect(jsonPath("$.nextAfter").isEmpty());
        verify(authority).authorize(space,token,false,"00-"+trace+"-"+"a".repeat(16)+"-01");verify(store).usages(space,concept,ClaimStore.UsageRole.OBJECT,null,20);verifyNoMoreInteractions(store);
        assertThat(context.getBean(MeterRegistry.class).get("brain.knowledge.claim.reads").tag("type","usage-object").counter().count()).isEqualTo(1);
    }
    @Test void predicateRoleAndMaximumExclusiveCursorReachTheStoreExactly()throws Exception{
        when(store.usages(space,concept,ClaimStore.UsageRole.PREDICATE,claim,100)).thenReturn(new ClaimStore.UsagePage(List.of(),null));
        mvc.perform(trusted(get(path)).param("role","PREDICATE").param("after",claim.toString()).param("limit","100")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(store).usages(space,concept,ClaimStore.UsageRole.PREDICATE,claim,100);
        assertThat(context.getBean(MeterRegistry.class).get("brain.knowledge.claim.reads").tag("type","usage-predicate").counter().count()).isEqualTo(1);
    }
    @Test void strictRoleCursorLimitAndSingletonKeysRejectBeforeAnyCanonicalRead()throws Exception{
        for(String role:List.of("SUBJECT","object"," PREDICATE","OBJECT ","","OBJECT,PREDICATE"))mvc.perform(trusted(get(path)).param("role",role)).andExpect(status().isBadRequest());
        for(String limit:List.of("0","101","01","+1","-1","1.0","1e0","2147483648","9223372036854775808",""))mvc.perform(trusted(get(path)).param("limit",limit)).andExpect(status().isBadRequest());
        for(String key:List.of("role","after","limit"))mvc.perform(trusted(get(path)).param(key,"1","2")).andExpect(status().isBadRequest());
        for(String key:List.of("before","q","state","visibility","subjectConceptId"))mvc.perform(trusted(get(path)).param(key,"1")).andExpect(status().isBadRequest());
        for(String after:List.of(UUID.randomUUID().toString(),"ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF","1-1-7000-8000-1",""," "+claim))mvc.perform(trusted(get(path)).param("after",after)).andExpect(status().isBadRequest());
        verifyNoInteractions(store,authority);
    }
    @Test void rawPathIdsAndEveryNonemptyGetBodyAreRejectedThroughRealServlets()throws Exception{
        for(String invalid:List.of("ABCDEFAB-CDEF-7ABC-8ABC-ABCDEFABCDEF","1-1-7000-8000-1",UUID.randomUUID().toString(),"not-an-id")){
            mvc.perform(trusted(get(path.replace(space.toString(),invalid)))).andExpect(status().isBadRequest());
            mvc.perform(trusted(get(path.replace(concept.toString(),invalid)))).andExpect(status().isBadRequest());
        }
        for(String body:List.of(" ","{}","null"))mvc.perform(trusted(get(path)).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void httpsUniqueCredentialAndFreshDenialsFailClosedBeforeStore()throws Exception{
        mvc.perform(trusted(get(path)).secure(false).header("X-Forwarded-Proto","https")).andExpect(status().isBadRequest());
        mvc.perform(trusted(get(path)).cookie(new Cookie("__Host-brain_guest","b".repeat(43)))).andExpect(status().isUnauthorized());
        verifyNoInteractions(store,authority);
        for(HttpStatus denied:List.of(HttpStatus.UNAUTHORIZED,HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){
            doThrow(new ResponseStatusException(denied)).when(authority).authorize(eq(space),eq(token),anyBoolean(),anyString());mvc.perform(trusted(get(path))).andExpect(status().is(denied.value()));
        }verifyNoInteractions(store);
    }
    @Test void unknownForeignOrPublicIdentityAndDatabaseFailureRetain404Or503()throws Exception{
        for(HttpStatus status:List.of(HttpStatus.NOT_FOUND,HttpStatus.SERVICE_UNAVAILABLE)){
            doThrow(new ResponseStatusException(status)).when(store).usages(space,concept,ClaimStore.UsageRole.OBJECT,null,20);
            mvc.perform(trusted(get(path))).andExpect(status().is(status.value()));
        }
        UUID foreign=ids.next();doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(authority).authorize(eq(foreign),eq(token),anyBoolean(),anyString());
        mvc.perform(trusted(get(path.replace(space.toString(),foreign.toString())))).andExpect(status().isNotFound());verify(store,times(2)).usages(space,concept,ClaimStore.UsageRole.OBJECT,null,20);verifyNoMoreInteractions(store);
    }
    @Test void onlyThePrivateReadRouteIsExposedAndNoWriteReachesStore()throws Exception{
        for(var write:List.of(post(path),put(path),delete(path)))mvc.perform(trusted(write)).andExpect(status().isMethodNotAllowed());
        for(String denied:List.of(path.replace("/api/spaces/","/api/public/spaces/"),"/internal/knowledge/claim-usages","/actuator/env"))mvc.perform(trusted(get(denied))).andExpect(status().isForbidden());
        mvc.perform(trusted(get(path+"/"+claim))).andExpect(status().isNotFound());verifyNoInteractions(store,authority);
    }
    @Test void disabledApiHasNoControllerAndDeniesBeforeAuthority()throws Exception{
        start(false);assertThat(context.getBeansOfType(ClaimUsageController.class)).isEmpty();mvc.perform(trusted(get(path))).andExpect(status().isForbidden());verifyNoInteractions(store,authority);
    }
}
