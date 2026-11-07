package io.knowledgebrain.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.identity.login.enabled",havingValue="true")
class RegisteredLoginController {
    static final String BINDING="__Host-brain_oidc";private final OidcLoginService login;private final String origin;private final MeterRegistry metrics;
    RegisteredLoginController(OidcLoginService login,@Value("${brain.identity.browser-origin}") String origin,MeterRegistry metrics){this.login=login;this.origin=origin;this.metrics=metrics;}
    @PostMapping(value="/api/identity/login",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> start(HttpServletRequest request){secure(request);if(Collections.list(request.getHeaders("Origin")).size()!=1||!origin.equals(request.getHeader("Origin"))||(request.getHeader("Sec-Fetch-Site")!=null&&!"same-origin".equals(request.getHeader("Sec-Fetch-Site"))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
        byte[] trace=new byte[16];new java.security.SecureRandom().nextBytes(trace);String parent=request.getHeader("traceparent");String traceId=parent!=null&&parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")&&!parent.substring(3,35).equals("0".repeat(32))&&!parent.substring(36,52).equals("0".repeat(16))?parent.substring(3,35):HexFormat.of().formatHex(trace);var started=login.start(GuestSessionController.cookie(request),traceId);metrics.counter("brain.identity.login","outcome","started").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Set-Cookie",cookie(BINDING,started.binding(),Duration.ofMinutes(5))).body(Map.of("authorizationUrl",started.authorizationUrl()));}
    @GetMapping("/api/identity/login/callback")
    ResponseEntity<Void> callback(HttpServletRequest request){secure(request);for(String key:List.of("state","code"))if(request.getParameterValues(key)==null||request.getParameterValues(key).length!=1)throw GuestSessionService.unauthorized();
        String binding=null;if(request.getCookies()!=null)for(var c:request.getCookies())if(BINDING.equals(c.getName())){if(binding!=null)throw GuestSessionService.unauthorized();binding=c.getValue();}
        var issued=login.callback(request.getParameter("state"),binding,GuestSessionController.cookie(request),request.getParameter("code"));metrics.counter("brain.identity.login","outcome","completed").increment();return ResponseEntity.status(303).cacheControl(CacheControl.noStore()).header("Location","/graph").header("Set-Cookie",cookie(GuestSessionService.COOKIE,issued.token(),Duration.ofHours(12)),cookie(BINDING,"",Duration.ZERO)).build();}
    @GetMapping("/api/identity/account-session")
    ResponseEntity<OidcLoginService.Current> current(HttpServletRequest request){secure(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(login.current(GuestSessionController.cookie(request)));}
    private void secure(HttpServletRequest r){if(!r.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required");}
    private String cookie(String name,String value,Duration age){return ResponseCookie.from(name,value).secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(age).build().toString();}
}
