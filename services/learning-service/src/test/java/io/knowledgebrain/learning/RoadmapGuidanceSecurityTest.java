package io.knowledgebrain.learning;

import java.util.*;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RoadmapGuidanceSecurityTest {
 @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class,RoadmapController.class,RoadmapGuidanceController.class,RoadmapErrors.class})
 static class Web {@Bean RoadmapStore roots(){return mock(RoadmapStore.class);}@Bean RoadmapGuidanceStore guidance(){return mock(RoadmapGuidanceStore.class);}@Bean SpaceAuthorizationClient authority(){return mock(SpaceAuthorizationClient.class);}@Bean MeterRegistry metrics(){return new SimpleMeterRegistry();}}
 AnnotationConfigWebApplicationContext context;MockMvc mvc;RoadmapGuidanceStore store;SpaceAuthorizationClient authority;
 UUID space=RoadmapGuidanceRulesTest.id(901),roadmap=RoadmapGuidanceRulesTest.id(900),actor=RoadmapGuidanceRulesTest.id(902);String token="a".repeat(43),path="/api/spaces/"+space+"/roadmaps/"+roadmap+"/learning-guidance";
 void start(boolean enabled){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("guidance-security",Map.of("brain.learning.api.enabled",Boolean.toString(enabled),"brain.learning.browser-origin","https://localhost:8443")));context.register(Web.class);context.refresh();store=context.getBean(RoadmapGuidanceStore.class);authority=context.getBean(SpaceAuthorizationClient.class);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();when(authority.authorize(eq(space),eq(token),eq(false),anyString())).thenReturn(actor);}
 @AfterEach void close(){if(context!=null)context.close();}
 @Test void enabledExactGetUsesFreshAuthorityAndRevocationStopsCanonicalRead()throws Exception{
  start(true);when(store.read(space,roadmap)).thenReturn(RoadmapGuidanceRules.compute(RoadmapGuidanceRulesTest.graph(0,List.of()),List.of()));var cookie=new Cookie("__Host-brain_guest",token);
  mvc.perform(get(path).secure(true).cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.algorithm").value("GRAPH_PROGRESS_V1")).andExpect(jsonPath("$.visibility").value("PRIVATE"));
  when(authority.authorize(eq(space),eq(token),eq(false),anyString())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));mvc.perform(get(path).secure(true).cookie(cookie)).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));verify(store).read(space,roadmap);verifyNoMoreInteractions(store);
 }
 @Test void featureDisabledDeniesExactReadBeforeAuthority()throws Exception{start(false);mvc.perform(get(path).secure(true)).andExpect(status().isForbidden());verifyNoInteractions(store,authority);}
 @Test void methodsAliasesAndSubpathsRemainDenied()throws Exception{
  start(true);for(var request:List.of(post(path),put(path),delete(path),patch(path),head(path),options(path),get(path+"/extra"),get(path+"/"),get("/api/public/spaces/"+space+"/roadmaps/"+roadmap+"/learning-guidance"),get("/api/groups/"+space+"/roadmaps/"+roadmap+"/learning-guidance")))mvc.perform(request.secure(true).cookie(new Cookie("__Host-brain_guest",token))).andExpect(status().isForbidden());verifyNoInteractions(store,authority);
 }
 @Test void exactGetRejectsAllQueryAndBodyOverridesBeforeAuthority()throws Exception{
  start(true);var cookie=new Cookie("__Host-brain_guest",token);mvc.perform(get(path).secure(true).cookie(cookie).param("actorId",actor.toString())).andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","no-store"));mvc.perform(get(path).secure(true).cookie(cookie).contentType("application/json").content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(store,authority);
 }
}
