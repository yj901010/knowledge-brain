package io.knowledgebrain.brainquery;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class NoteSnapshotClientTest {
 UUID a=UUID.fromString("01900000-0000-7000-8000-000000000001"),b=UUID.fromString("01900000-0000-7000-8000-000000000002");
 NoteSnapshotClient.Item note(UUID id,String state,String body) {return new NoteSnapshotClient.Item(id,a,b,2,"PRIVATE",state,state.equals("ACTIVE")?"title":"",body);}
 @Test void validatesOrderedBoundedCanonicalPages() {
  var page=new NoteSnapshotClient.Page(b,a,List.of(note(a,"ACTIVE","body")));NoteSnapshotClient.validate(page,null,b);
  assertThatThrownBy(()->NoteSnapshotClient.validate(page,a,b)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->NoteSnapshotClient.validate(page,null,a)).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->NoteSnapshotClient.validate(new NoteSnapshotClient.Page(b,a,List.of(note(b,"ACTIVE","body"),note(a,"ACTIVE","body"))),null,b)).isInstanceOf(IllegalStateException.class);
 }
 @Test void retiredSnapshotsCannotContainPrivateTextAndInvalidIdsAreRejected() {
  assertThat(note(a,"RETIRED","").projection().state()).isEqualTo("RETIRED");
  assertThatThrownBy(()->note(a,"RETIRED","secret").projection()).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->note(UUID.randomUUID(),"ACTIVE","body").projection()).isInstanceOf(IllegalStateException.class);
 }
}
