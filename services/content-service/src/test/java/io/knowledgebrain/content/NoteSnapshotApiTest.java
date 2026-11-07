package io.knowledgebrain.content;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class NoteSnapshotApiTest {
 @Test void deniesHttpMissingWrongDuplicateAndBrowserCredentialsBeforeDatabase() {
  var db=mock(DataSource.class);var api=new NoteSnapshotController(db,"a".repeat(43),new SimpleMeterRegistry());
  for(int mode=0;mode<5;mode++) {
   var request=new MockHttpServletRequest();request.setSecure(mode!=0);
   if(mode!=1)request.addHeader("Authorization","Bearer "+(mode==2?"b":"a").repeat(43));
   if(mode==3)request.addHeader("Authorization","Bearer "+"a".repeat(43));
   if(mode==4)request.addHeader("Cookie","guest=anything");
   assertThatThrownBy(()->api.get(request)).isInstanceOf(ResponseStatusException.class);
  }
  verifyNoInteractions(db);
 }
 @Test void rejectsUnknownRepeatedOversizeAndInvalidCursorBeforeDatabase() {
  var db=mock(DataSource.class);var api=new NoteSnapshotController(db,"a".repeat(43),new SimpleMeterRegistry());
  for(String name:new String[]{"limit","spaceId","after","through"}) {
   var request=new MockHttpServletRequest();request.setSecure(true);request.addHeader("Authorization","Bearer "+"a".repeat(43));request.addParameter(name,"invalid");
   assertThatThrownBy(()->api.get(request)).isInstanceOf(ResponseStatusException.class).satisfies(e->assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(400));
  }
  var request=new MockHttpServletRequest();request.setSecure(true);request.addHeader("Authorization","Bearer "+"a".repeat(43));request.addParameter("limit",new String[]{"1","2"});
  assertThatThrownBy(()->api.get(request)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(db);
 }
}
