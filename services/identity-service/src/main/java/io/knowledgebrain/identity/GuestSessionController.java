package io.knowledgebrain.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.MeterRegistry;

@RestController
@ConditionalOnProperty(name="brain.identity.api.enabled",havingValue="true")
class GuestSessionController {
    private final GuestSessionService sessions;
    private final String origin,serviceKey;
    private final MeterRegistry metrics;
    private final SecureRandom random=new SecureRandom();
    GuestSessionController(GuestSessionService sessions,MeterRegistry metrics,@Value("${brain.identity.browser-origin}") String origin,
            @Value("${brain.identity.introspection-key}") String serviceKey) {
        URI uri=URI.create(origin);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.getRawPath().isEmpty())throw new IllegalArgumentException("HTTPS browser origin required");
        if(!serviceKey.matches("[A-Za-z0-9_-]{43}"))throw new IllegalArgumentException("32-byte service credential required");
        this.sessions=sessions;this.metrics=metrics;this.origin=origin;this.serviceKey=serviceKey;
    }
    @PostMapping(value="/api/identity/guest-session",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GuestSessionService.Principal> create(HttpServletRequest request) {
        browserMutation(request);String token=cookie(request);
        if(token!=null)try{return principal(sessions.resolve(token));}catch(ResponseStatusException error){if(error.getStatusCode().value()!=401)throw error;}
        var issued=sessions.create(trace(request));metrics.counter("brain.identity.guest.sessions","outcome","created").increment();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(GuestSessionService.COOKIE,issued.token()).secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ofDays(180)).build().toString()).body(issued.principal());
    }
    @GetMapping("/api/identity/guest-session")
    ResponseEntity<GuestSessionService.Principal> current(HttpServletRequest request) {
        secure(request);return principal(sessions.resolve(cookie(request)));
    }
    @PostMapping(value="/api/identity/guest-session/revoke",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> revoke(HttpServletRequest request) {
        browserMutation(request);sessions.revoke(cookie(request));metrics.counter("brain.identity.guest.sessions","outcome","revoked").increment();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).header(HttpHeaders.SET_COOKIE,
            ResponseCookie.from(GuestSessionService.COOKIE,"").secure(true).httpOnly(true).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString()).build();
    }
    record Credential(String token) { @Override public String toString() { return "Credential[REDACTED]"; } }
    @PostMapping(value="/internal/identity/guest-introspection",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GuestSessionService.Principal> introspect(HttpServletRequest request,@RequestBody Credential credential) {
        secure(request);String authorization=request.getHeader(HttpHeaders.AUTHORIZATION);
        if(Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size()!=1 || authorization==null || !MessageDigest.isEqual(("Bearer "+serviceKey).getBytes(StandardCharsets.US_ASCII),authorization.getBytes(StandardCharsets.US_ASCII)))throw GuestSessionService.unauthorized();
        return principal(sessions.resolve(credential.token()));
    }
    private ResponseEntity<GuestSessionService.Principal> principal(GuestSessionService.Principal principal) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(principal); }
    private void browserMutation(HttpServletRequest request) {
        secure(request);
        if(Collections.list(request.getHeaders("Origin")).size()!=1 || !origin.equals(request.getHeader("Origin")) || (request.getHeader("Sec-Fetch-Site")!=null && !"same-origin".equals(request.getHeader("Sec-Fetch-Site"))))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin request required");
    }
    private void secure(HttpServletRequest request) { if(!request.isSecure())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"HTTPS required"); }
    static String cookie(HttpServletRequest request) {
        String token=null;
        if(request.getCookies()!=null)for(var cookie:request.getCookies())if(GuestSessionService.COOKIE.equals(cookie.getName())) {
            if(token!=null)throw GuestSessionService.unauthorized();token=cookie.getValue();
        }
        return token;
    }
    private String trace(HttpServletRequest request) {
        String parent=request.getHeader("traceparent");
        if(parent!=null && parent.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}") && !parent.substring(3,35).equals("0".repeat(32)) && !parent.substring(36,52).equals("0".repeat(16)))return parent.substring(3,35);
        byte[] value=new byte[16];do { random.nextBytes(value); } while(Arrays.equals(value,new byte[16]));return HexFormat.of().formatHex(value);
    }
}
