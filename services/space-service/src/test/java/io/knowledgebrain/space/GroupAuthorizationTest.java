package io.knowledgebrain.space;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GroupAuthorizationTest {
 GroupSpaceStore store=mock(GroupSpaceStore.class);IdentityPrincipalClient identity=mock(IdentityPrincipalClient.class);GroupAuthorizationController api=new GroupAuthorizationController(store,mock(GroupProgressPolicyStore.class),identity,"a".repeat(43));UuidV7 ids=new UuidV7(Clock.systemUTC());String token="b".repeat(43);
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.setSecure(true);r.addHeader("Authorization","Bearer "+"a".repeat(43));return r;}
 @Test void requiresRegisteredPrincipalOnEveryCall(){var principal=ids.next();when(identity.resolve(token,null)).thenReturn(new IdentityPrincipalClient.Principal(principal,"GUEST",Instant.now().plusSeconds(60)));assertThrows(ResponseStatusException.class,()->api.access(new GroupAuthorizationController.Check(token,List.of()),request()));verifyNoInteractions(store);when(identity.resolve(token,null)).thenReturn(new IdentityPrincipalClient.Principal(principal,"REGISTERED",Instant.now().plusSeconds(60)));when(store.access(principal,List.of())).thenReturn(List.of());assertEquals(principal,api.access(new GroupAuthorizationController.Check(token,List.of()),request()).getBody().principalId());verify(identity,times(2)).resolve(token,null);}
 @Test void browserCannotInvokeInternalCapability(){var r=request();r.removeHeader("Authorization");r.addHeader("Cookie","__Host-brain_guest="+token);assertThrows(ResponseStatusException.class,()->api.access(new GroupAuthorizationController.Check(token,List.of()),r));verifyNoInteractions(identity,store);}
 @Test void duplicateServiceHeadersAndInsecureAreDenied(){var r=request();r.addHeader("Authorization","Bearer "+"a".repeat(43));assertThrows(ResponseStatusException.class,()->api.provision(new GroupAuthorizationController.Provision(token,ids.next()),r));var insecure=request();insecure.setSecure(false);assertThrows(ResponseStatusException.class,()->api.access(new GroupAuthorizationController.Check(token,List.of()),insecure));verifyNoInteractions(identity,store);}
 @Test void strictInternalJsonAndRequestSizeAreBoundedBeforeIdentity()throws Exception{for(String json:List.of("{\"token\":\""+token+"\",\"groupIds\":[],\"principalId\":\"forged\"}","{\"token\":\""+token+"\",\"token\":\""+token+"\",\"groupIds\":[]}","{\"token\":\""+token+"\",\"groupIds\":[]} {}")){var r=request();r.setContentType("application/json");r.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThrows(ResponseStatusException.class,()->api.accessRequest(r));}var big=request();big.setContentType("application/json");big.setContent(new byte[8193]);assertThrows(ResponseStatusException.class,()->api.provisionRequest(big));verifyNoInteractions(identity,store);}

}
