package io.knowledgebrain.content;
import java.time.Clock;
import java.util.*;
import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DailyApiTest {
 NoteStore store;SpaceAuthorizationClient authority;DailyNoteController api;UuidV7 ids=new UuidV7(Clock.systemUTC());UUID space,actor;
 @BeforeEach void setup(){store=mock(NoteStore.class);authority=mock(SpaceAuthorizationClient.class);var metrics=new SimpleMeterRegistry();api=new DailyNoteController(store,new NoteController(store,authority,metrics,"https://localhost:8443"),metrics);space=ids.next();actor=ids.next();when(authority.authorize(eq(space),eq("a".repeat(43)),anyBoolean(),anyString())).thenReturn(actor);}
 MockHttpServletRequest request(){var r=new MockHttpServletRequest("POST","/api/spaces/"+space+"/daily-notes");r.setSecure(true);r.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)));r.addHeader("Origin","https://localhost:8443");r.setContentType("application/json");return r;}
 @Test void noOriginDuplicateCredentialAndAuthorityOutageNeverReachStore(){var wrong=request();wrong.removeHeader("Origin");assertThatThrownBy(()->api.open(space,wrong)).isInstanceOf(ResponseStatusException.class);var duplicate=request();duplicate.setCookies(new Cookie("__Host-brain_guest","a".repeat(43)),new Cookie("__Host-brain_guest","b".repeat(43)));assertThatThrownBy(()->api.open(space,duplicate)).isInstanceOf(ResponseStatusException.class);when(authority.authorize(eq(space),anyString(),anyBoolean(),anyString())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));assertThatThrownBy(()->api.today(space,"Asia/Seoul",request())).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(store);}
 @Test void unknownDuplicateTrailingAndOversizeBodiesAreRejectedBeforeStore(){for(String body:List.of("{}","{\"date\":\"2026-10-07\",\"timezone\":\"UTC\",\"markdown\":\"lost\"}","{\"date\":\"2026-10-07\",\"date\":\"2026-10-08\",\"timezone\":\"UTC\"}","{\"date\":\"2026-10-07\",\"timezone\":\"UTC\"} {}")){var r=request();r.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));assertThatThrownBy(()->api.open(space,r)).isInstanceOf(ResponseStatusException.class);}var huge=request();huge.setContent(new byte[1025]);assertThatThrownBy(()->api.open(space,huge)).isInstanceOf(ResponseStatusException.class);verifyNoInteractions(store);}
 @Test void missingTimezoneReturns400ThroughMvcWithoutCallingStore() throws Exception {
 var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(api).setControllerAdvice(new ApiErrors()).build();mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/spaces/"+space+"/daily-notes/today").secure(true).cookie(new Cookie("__Host-brain_guest","a".repeat(43)))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());verifyNoInteractions(store);
 }
}
