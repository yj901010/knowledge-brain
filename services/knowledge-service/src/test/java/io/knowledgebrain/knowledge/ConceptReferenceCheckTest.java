package io.knowledgebrain.knowledge;

import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptReferenceCheckTest {
 ConceptStore store;SpaceAuthorizationClient authority;ConceptCheckController api;
 UuidV7 ids=new UuidV7(Clock.systemUTC());UUID destination,publicSpace,actor,privateId,publicId,secondPublic;
 String token="a".repeat(43),key="b".repeat(43),parent="00-"+"c".repeat(32)+"-"+"d".repeat(16)+"-01";
 @BeforeEach void prepare(){store=mock(ConceptStore.class);authority=mock(SpaceAuthorizationClient.class);api=new ConceptCheckController(store,authority,key);destination=ids.next();publicSpace=ids.next();actor=ids.next();privateId=ids.next();publicId=ids.next();secondPublic=ids.next();
  when(authority.authorize(destination,token,false,parent)).thenReturn(actor);
  when(store.reference(destination,privateId)).thenReturn(new ConceptStore.Reference(destination,"PRIVATE"));
  for(var id:List.of(publicId,secondPublic))when(store.reference(destination,id)).thenReturn(new ConceptStore.Reference(publicSpace,"PUBLIC"));
  when(authority.authorize(publicSpace,token,"PUBLIC_CONCEPT_READ","PUBLIC",parent)).thenReturn(actor);
 }
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/internal/knowledge/concept-check");r.setSecure(true);r.addHeader("Authorization","Bearer "+key);r.addHeader("traceparent",parent);return r;}
 void status(int expected,org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(expected));}
 @Test void mixedReferencesRemainIdsOnlyOrderedAndPublicAuthorityIsCheckedOncePerSpace(){var wanted=List.of(publicId,privateId,secondPublic);var response=api.check(new ConceptCheckController.Check(destination,token,wanted),request());assertThat(response.getBody()).containsExactlyElementsOf(wanted);assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");verify(authority,times(1)).authorize(publicSpace,token,"PUBLIC_CONCEPT_READ","PUBLIC",parent);verify(store,never()).get(any(),any());}
 @Test void publicVisibilityAloneCannotBypassSpaceAuthority(){when(authority.authorize(publicSpace,token,"PUBLIC_CONCEPT_READ","PUBLIC",parent)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));status(404,()->api.check(new ConceptCheckController.Check(destination,token,List.of(publicId)),request()));}
 @Test void authorityOutageOrPrincipalChangeRejectsEntireMixedBatch(){when(authority.authorize(publicSpace,token,"PUBLIC_CONCEPT_READ","PUBLIC",parent)).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));status(503,()->api.check(new ConceptCheckController.Check(destination,token,List.of(privateId,publicId)),request()));doReturn(ids.next()).when(authority).authorize(publicSpace,token,"PUBLIC_CONCEPT_READ","PUBLIC",parent);status(503,()->api.check(new ConceptCheckController.Check(destination,token,List.of(publicId)),request()));}
 @Test void invalidServiceCredentialsOrMalformedIdsCannotReadConceptStorage(){var insecure=request();insecure.setSecure(false);status(401,()->api.check(new ConceptCheckController.Check(destination,token,List.of(publicId)),insecure));var duplicate=request();duplicate.addHeader("Authorization","Bearer "+key);status(401,()->api.check(new ConceptCheckController.Check(destination,token,List.of(publicId)),duplicate));status(400,()->api.check(new ConceptCheckController.Check(destination,token,List.of(publicId,publicId)),request()));status(400,()->api.check(new ConceptCheckController.Check(destination,token,List.of(UUID.randomUUID())),request()));verifyNoInteractions(store,authority);}
 @Test void privateSpaceAuthorizationMustSucceedBeforeReferenceLookup(){when(authority.authorize(destination,token,false,parent)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));status(403,()->api.check(new ConceptCheckController.Check(destination,token,List.of(publicId)),request()));verifyNoInteractions(store);}
}
