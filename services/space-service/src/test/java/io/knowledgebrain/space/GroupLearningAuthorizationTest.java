package io.knowledgebrain.space;
import java.time.*;import org.junit.jupiter.api.Test;import org.springframework.web.server.ResponseStatusException;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class GroupLearningAuthorizationTest {
 @Test void onlyRegisteredActivePrivateGroupMembershipGrantsSharedRoadmaps(){
  var spaces=mock(PersonalSpaceStore.class);var identity=mock(IdentityPrincipalClient.class);var api=new LearningAuthorizationController(spaces,identity,"a".repeat(43));var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();
  when(identity.resolve("b".repeat(43),null)).thenReturn(new IdentityPrincipalClient.Principal(actor,"REGISTERED",Instant.now().plusSeconds(60)));
  for(String role:new String[]{"OWNER","MEMBER","VIEWER"}){
   when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,"GROUP","PRIVATE",role));
   assertEquals(actor,api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),"GROUP_ROADMAP_READ"),new LearningAuthorizationTest().request()).getBody().principalId());
   if(role.equals("OWNER"))assertEquals("PRIVATE",api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),"GROUP_ROADMAP_WRITE"),new LearningAuthorizationTest().request()).getBody().visibility());
   else assertEquals(403,assertThrows(ResponseStatusException.class,()->api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),"GROUP_ROADMAP_WRITE"),new LearningAuthorizationTest().request())).getStatusCode().value());
   for(String personal:new String[]{"ROADMAP_READ","ROADMAP_WRITE"})assertEquals(404,assertThrows(ResponseStatusException.class,()->api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),personal),new LearningAuthorizationTest().request())).getStatusCode().value());
  }
  for(String kind:new String[]{"PERSONAL","PUBLIC","ORGANIZATION"}){when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,kind,"PRIVATE","OWNER"));assertThrows(ResponseStatusException.class,()->api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),"GROUP_ROADMAP_READ"),new LearningAuthorizationTest().request()));}
  when(spaces.visible(space,actor)).thenReturn(null);assertThrows(ResponseStatusException.class,()->api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),"GROUP_ROADMAP_READ"),new LearningAuthorizationTest().request()));
  when(identity.resolve("b".repeat(43),null)).thenReturn(new IdentityPrincipalClient.Principal(actor,"GUEST",Instant.now().plusSeconds(60)));assertEquals(403,assertThrows(ResponseStatusException.class,()->api.authorize(new LearningAuthorizationController.Check(space,"b".repeat(43),"GROUP_ROADMAP_READ"),new LearningAuthorizationTest().request())).getStatusCode().value());
 }
}
