package io.knowledgebrain.space;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class QueryAuthorizationTest {
 @Test void freshPrivateMembershipPermitsReadOnlySearchAndNeverWriteActions() {
  var spaces=mock(PersonalSpaceStore.class);var identity=mock(IdentityPrincipalClient.class);var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();var api=new QueryAuthorizationController(spaces,identity,"k".repeat(43));
  var request=new MockHttpServletRequest();request.setSecure(true);request.addHeader("Authorization","Bearer "+"k".repeat(43));when(identity.resolve("a".repeat(43),null)).thenReturn(new IdentityPrincipalClient.Principal(actor,"GUEST",Instant.now().plusSeconds(300)));when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,"PERSONAL","PRIVATE","VIEWER"));
  assertThat(api.authorize(new QueryAuthorizationController.Check(space,"a".repeat(43),"NOTE_SEARCH"),request).getBody().principalId()).isEqualTo(actor);
  assertThat(api.authorize(new QueryAuthorizationController.Check(space,"a".repeat(43),"NOTE_GRAPH"),request).getBody().action()).isEqualTo("NOTE_GRAPH");
  for(String action:new String[]{"NOTE_READ","NOTE_WRITE","MEDIA_WRITE"})assertThatThrownBy(()->api.authorize(new QueryAuthorizationController.Check(space,"a".repeat(43),action),request)).isInstanceOf(ResponseStatusException.class);
  when(spaces.visible(space,actor)).thenReturn(null);assertThatThrownBy(()->api.authorize(new QueryAuthorizationController.Check(space,"a".repeat(43),"NOTE_SEARCH"),request)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
  verify(identity,times(3)).resolve("a".repeat(43),null);
 }
 @Test void serviceCredentialHttpsAndCookieViolationsNeverResolveIdentity() {
  var spaces=mock(PersonalSpaceStore.class);var identity=mock(IdentityPrincipalClient.class);var api=new QueryAuthorizationController(spaces,identity,"k".repeat(43));var space=new UuidV7(Clock.systemUTC()).next();
  for(int mode=0;mode<4;mode++) {var request=new MockHttpServletRequest();request.setSecure(mode!=0);if(mode!=1)request.addHeader("Authorization","Bearer "+"k".repeat(43));if(mode==2)request.addHeader("Authorization","Bearer duplicate");if(mode==3)request.addHeader("Cookie","guest=anything");assertThatThrownBy(()->api.authorize(new QueryAuthorizationController.Check(space,"a".repeat(43),"NOTE_SEARCH"),request)).isInstanceOf(ResponseStatusException.class);}
  verifyNoInteractions(spaces,identity);
 }
}
