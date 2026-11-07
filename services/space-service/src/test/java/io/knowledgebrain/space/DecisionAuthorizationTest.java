package io.knowledgebrain.space;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;import org.springframework.mock.web.MockHttpServletRequest;import org.springframework.web.server.ResponseStatusException;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class DecisionAuthorizationTest {
 @Test void decisionActionsRequireDedicatedContentKeyCurrentPrivateMembershipAndWriteRole(){
  var spaces=mock(PersonalSpaceStore.class);var identity=mock(IdentityPrincipalClient.class);var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();var api=new ContentAuthorizationController(spaces,identity,"k".repeat(43));
  var r=new MockHttpServletRequest("POST","/internal/space/content-authorization");r.setSecure(true);r.addHeader("Authorization","Bearer "+"k".repeat(43));when(identity.resolve("a".repeat(43),null)).thenReturn(new IdentityPrincipalClient.Principal(actor,"GUEST",Instant.now().plusSeconds(300)));
  when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,"PERSONAL","PRIVATE","VIEWER"));assertEquals(actor,api.authorize(new ContentAuthorizationController.Check(space,"a".repeat(43),"BOOKMARK_READ"),r).getBody().principalId());
  assertEquals(403,assertThrows(ResponseStatusException.class,()->api.authorize(new ContentAuthorizationController.Check(space,"a".repeat(43),"BOOKMARK_WRITE"),r)).getStatusCode().value());
  when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,"PERSONAL","PRIVATE","EDITOR"));assertEquals("BOOKMARK_WRITE",api.authorize(new ContentAuthorizationController.Check(space,"a".repeat(43),"BOOKMARK_WRITE"),r).getBody().action());
  when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,"PUBLIC","PUBLIC","OWNER"));assertEquals(404,assertThrows(ResponseStatusException.class,()->api.authorize(new ContentAuthorizationController.Check(space,"a".repeat(43),"BOOKMARK_READ"),r)).getStatusCode().value());
  r.removeHeader("Authorization");assertEquals(401,assertThrows(ResponseStatusException.class,()->api.authorize(new ContentAuthorizationController.Check(space,"a".repeat(43),"BOOKMARK_READ"),r)).getStatusCode().value());verify(identity,times(4)).resolve("a".repeat(43),null);
 }
}
