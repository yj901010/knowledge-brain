package io.knowledgebrain.collaboration;
import java.util.*;import org.junit.jupiter.api.*;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
class CourseLearningContextTest {
 @Test void referenceAlwaysUsesFreshSelfGrantAndNeverAcceptsQueryIdentity()throws Exception{var f=new StudyApiTest();f.setup();var store=mock(CourseEnrollmentStore.class);var api=new CourseLearningContextController(store,f.api,new SimpleMeterRegistry());api.context(f.group.toString(),f.id.toString(),f.req("GET",null));verify(store).learningContext(f.group,f.space,f.id,f.actor,"OWNER");var spoof=f.req("GET",null);spoof.addParameter("participantId",f.actor.toString());assertThrows(org.springframework.web.server.ResponseStatusException.class,()->api.context(f.group.toString(),f.id.toString(),spoof));verifyNoMoreInteractions(store);}
}
