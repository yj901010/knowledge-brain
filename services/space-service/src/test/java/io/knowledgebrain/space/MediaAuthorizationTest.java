package io.knowledgebrain.space;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class MediaAuthorizationTest {
    @Test void verifiedMembershipRolesAndServiceKeyControlNotePermissions() {
        var spaces=mock(PersonalSpaceStore.class);var identity=mock(IdentityPrincipalClient.class);var ids=new UuidV7(Clock.systemUTC());var space=ids.next();var actor=ids.next();var api=new MediaAuthorizationController(spaces,identity,"k".repeat(43));
        var request=new MockHttpServletRequest("POST","/internal/space/media-authorization");request.setSecure(true);request.addHeader("Authorization","Bearer "+"k".repeat(43));when(identity.resolve("a".repeat(43),null)).thenReturn(new IdentityPrincipalClient.Principal(actor,"GUEST",Instant.now().plusSeconds(300)));
        when(spaces.visible(space,actor)).thenReturn(new PersonalSpaceStore.SpaceView(space,"PERSONAL","PRIVATE","VIEWER"));
        var read=api.authorize(new MediaAuthorizationController.Check(space,"a".repeat(43),"MEDIA_READ"),request);assertThat(read.getBody().principalId()).isEqualTo(actor);
        assertThatThrownBy(()->api.authorize(new MediaAuthorizationController.Check(space,"a".repeat(43),"MEDIA_WRITE"),request)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(403));
        when(spaces.visible(space,actor)).thenReturn(null);assertThatThrownBy(()->api.authorize(new MediaAuthorizationController.Check(space,"a".repeat(43),"MEDIA_READ"),request)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(404));
        request.removeHeader("Authorization");assertThatThrownBy(()->api.authorize(new MediaAuthorizationController.Check(space,"a".repeat(43),"MEDIA_READ"),request)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(401));
    }
}
