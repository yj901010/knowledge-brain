package io.knowledgebrain.knowledge;
import java.time.*;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class LearningConceptReferenceControllerTest {
 ConceptStore store;ConceptController browser;SpaceAuthorizationClient auth;LearningConceptReferenceController api;UUID space,concept,publicSpace,actor;MockHttpServletRequest request;
 @BeforeEach void setup(){var ids=new UuidV7(Clock.systemUTC());space=ids.next();concept=ids.next();publicSpace=ids.next();actor=ids.next();store=mock(ConceptStore.class);browser=mock(ConceptController.class);auth=mock(SpaceAuthorizationClient.class);request=new MockHttpServletRequest();request.setSecure(true);request.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));when(browser.parent(request)).thenReturn("trace");when(browser.authorize(space,request,false,"trace")).thenReturn(actor);api=new LearningConceptReferenceController(store,browser,auth,new SimpleMeterRegistry(),true);}
 @Test void privateReadReturnsOnlyOwnerMetadataAfterFreshAuthority(){when(store.learningReference(space,concept)).thenReturn(new ConceptStore.LearningReference(space,2,"Private title","PRIVATE"));var r=api.read(space,concept,request);assertEquals("no-store",r.getHeaders().getCacheControl());assertEquals(new LearningConceptReferenceController.Reference(space,concept,space,actor,2,"Private title","PRIVATE"),r.getBody());verifyNoInteractions(auth);reset(store);when(browser.authorize(space,request,false,"trace")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));assertThrows(ResponseStatusException.class,()->api.read(space,concept,request));verifyNoInteractions(store);}
 @Test void publicReferenceRequiresCurrentPublicSpaceReadBySamePrincipal(){when(store.learningReference(space,concept)).thenReturn(new ConceptStore.LearningReference(publicSpace,4,"Public title","PUBLIC"));when(auth.authorize(publicSpace,"a".repeat(43),"PUBLIC_CONCEPT_READ","PUBLIC","trace")).thenReturn(actor);assertEquals("PUBLIC",api.read(space,concept,request).getBody().visibility());when(auth.authorize(publicSpace,"a".repeat(43),"PUBLIC_CONCEPT_READ","PUBLIC","trace")).thenReturn(space);assertEquals(503,assertThrows(ResponseStatusException.class,()->api.read(space,concept,request)).getStatusCode().value());when(auth.authorize(publicSpace,"a".repeat(43),"PUBLIC_CONCEPT_READ","PUBLIC","trace")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));assertEquals(403,assertThrows(ResponseStatusException.class,()->api.read(space,concept,request)).getStatusCode().value());}
 @Test void featureDisabledAndMalformedRequestNeverBypassAuthority(){when(store.learningReference(space,concept)).thenReturn(new ConceptStore.LearningReference(publicSpace,4,"Public title","PUBLIC"));var disabled=new LearningConceptReferenceController(store,browser,auth,new SimpleMeterRegistry(),false);assertEquals(404,assertThrows(ResponseStatusException.class,()->disabled.read(space,concept,request)).getStatusCode().value());verifyNoInteractions(auth);reset(store,browser);request.addParameter("unknown","1");assertEquals(400,assertThrows(ResponseStatusException.class,()->api.read(space,concept,request)).getStatusCode().value());verifyNoInteractions(store,browser,auth);}
}
