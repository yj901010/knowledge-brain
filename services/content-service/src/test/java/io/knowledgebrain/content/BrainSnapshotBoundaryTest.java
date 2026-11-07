package io.knowledgebrain.content;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class BrainSnapshotBoundaryTest {
 @Test void canonicalFeedRejectsUnscopedBrowserCredentialsAndAmbiguousRequestsBeforeDatabase(){var source=mock(DataSource.class);var api=new BrainSnapshotController(source,"a".repeat(43));for(int n=0;n<6;n++){var r=new MockHttpServletRequest();r.setSecure(true);r.addHeader("Authorization","Bearer "+"a".repeat(43));r.addParameter("kind","NOTE_CONCEPTS");switch(n){case 0->r.addHeader("Cookie","__Host-brain_guest=anything");case 1->r.addHeader("Authorization","Bearer "+"a".repeat(43));case 2->r.setContent(new byte[]{1});case 3->r.addParameter("limit","6");case 4->r.addParameter("kind","NOTE");case 5->r.addParameter("through","bad");}assertThrows(ResponseStatusException.class,()->api.read(r));}verifyNoInteractions(source);}
}
