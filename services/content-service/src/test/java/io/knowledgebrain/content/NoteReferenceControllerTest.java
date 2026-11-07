package io.knowledgebrain.content;
import java.time.*;
import java.util.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NoteReferenceControllerTest {
 @Test void onlyActiveAuthorizedNoteMetadataLeavesOwner(){var ids=new UuidV7(Clock.systemUTC());UUID space=ids.next(),note=ids.next(),actor=ids.next();var store=mock(NoteStore.class);var authority=mock(NoteController.class);var request=new MockHttpServletRequest();when(authority.parent(request)).thenReturn("trace");when(authority.authorize(space,request,false,"trace")).thenReturn(actor);when(store.get(space,note)).thenReturn(new NoteStore.Note(note,space,"PRIVATE","ACTIVE",3,"Title","SECRET MARKDOWN",Instant.now(),Instant.now()));var api=new NoteReferenceController(store,authority);var r=api.reference(space,note,request);assertEquals("no-store",r.getHeaders().getCacheControl());assertEquals(new NoteReferenceController.Reference(space,note,actor,3,"Title","PRIVATE"),r.getBody());assertFalse(r.getBody().toString().contains("SECRET"));reset(store);when(authority.authorize(space,request,false,"trace")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));assertThrows(ResponseStatusException.class,()->api.reference(space,note,request));verifyNoInteractions(store);}
}
