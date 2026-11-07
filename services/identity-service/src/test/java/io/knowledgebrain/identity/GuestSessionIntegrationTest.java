package io.knowledgebrain.identity;

import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.Cookie;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_DATABASE_URL",matches=".+")
class GuestSessionIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;GuestSessionService sessions;
    String trace="c".repeat(32),key="A".repeat(43);
    @BeforeEach void prepare() {
        source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_DATABASE_URL"));source.setUser("brain_identity");source.setPassword(System.getenv("BRAIN_TEST_DATABASE_PASSWORD"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);
        sessions=new GuestSessionService(new GuestRegistrationStore(source,Clock.systemUTC()),source,Clock.systemUTC());
    }
    GuestSessionController controller() { return new GuestSessionController(sessions,new SimpleMeterRegistry(),"https://localhost:8443",key); }
    MockHttpServletRequest request() { var request=new MockHttpServletRequest();request.setSecure(true);request.addHeader("Origin","https://localhost:8443");return request; }
    @Test void randomOpaqueCredentialsPersistOnlyHashAndResolveAcrossRestart() {
        var first=sessions.create(trace);var second=sessions.create(trace);
        assertThat(first.token()).hasSize(43).isNotEqualTo(second.token());assertThat(first.principal().principalId().version()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT credential_hash FROM guest_identity WHERE principal_id=?",byte[].class,first.principal().principalId())).isEqualTo(GuestSessionService.hash(first.token()));
        assertThat(new GuestSessionService(new GuestRegistrationStore(source,Clock.systemUTC()),source,Clock.systemUTC()).resolve(first.token())).isEqualTo(first.principal());
        assertThat(first.toString()).doesNotContain(first.token());assertThat(first.principal().expiresAt()).isAfter(Instant.now().plus(Duration.ofDays(179)));
    }
    @Test void explicitStartAfterRevocationRotatesCredentialAndCreatesANewPrivateGuest() {
        var prior=sessions.create(trace);sessions.revoke(prior.token());var request=request();request.setCookies(new Cookie(GuestSessionService.COOKIE,prior.token()));
        var response=controller().create(request);assertThat(response.getStatusCode().value()).isEqualTo(201);assertThat(response.getBody().principalId()).isNotEqualTo(prior.principal().principalId());assertThat(response.getBody().kind()).isEqualTo("GUEST");
    }
    @Test void cookieAttributesRestoreSameIdentityWithoutAdditionalOutbox() {
        var created=controller().create(request());assertThat(created.getStatusCode().value()).isEqualTo(201);
        String cookie=created.getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).startsWith("__Host-brain_guest=").contains("Path=/","Secure","HttpOnly","SameSite=Lax","Max-Age=15552000").doesNotContain("Domain=");
        String token=cookie.substring(cookie.indexOf('=')+1,cookie.indexOf(';'));
        var returning=request();returning.setCookies(new Cookie(GuestSessionService.COOKIE,token));
        var restored=controller().create(returning);assertThat(restored.getBody()).isEqualTo(created.getBody());assertThat(restored.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=?",Long.class,created.getBody().principalId())).isEqualTo(1);
    }
    @Test void rejectsMalformedExpiredRevokedAndRetiredCredentials() {
        var issued=sessions.create(trace);
        for(String invalid:List.of("","attacker",issued.token()+"=","A".repeat(42)+"B"))assertThatThrownBy(()->sessions.resolve(invalid)).isInstanceOf(ResponseStatusException.class);
        var future=Clock.fixed(issued.principal().expiresAt().plusSeconds(1),ZoneOffset.UTC);
        assertThatThrownBy(()->new GuestSessionService(new GuestRegistrationStore(source,future),source,future).resolve(issued.token())).isInstanceOf(ResponseStatusException.class);
        sessions.revoke(issued.token());assertThatThrownBy(()->sessions.resolve(issued.token())).isInstanceOf(ResponseStatusException.class);
        var retired=sessions.create(trace);jdbc.update("UPDATE guest_identity SET state='RETIRED' WHERE principal_id=?",retired.principal().principalId());
        assertThatThrownBy(()->sessions.resolve(retired.token())).isInstanceOf(ResponseStatusException.class);
    }
    @Test void mutationRequiresHttpsExactOriginAndUnambiguousCookie() {
        var wrongOrigin=request();wrongOrigin.removeHeader("Origin");wrongOrigin.addHeader("Origin","https://evil.example");
        assertThatThrownBy(()->controller().create(wrongOrigin)).isInstanceOf(ResponseStatusException.class);
        var missing=request();missing.removeHeader("Origin");assertThatThrownBy(()->controller().create(missing)).isInstanceOf(ResponseStatusException.class);
        var duplicateOrigin=request();duplicateOrigin.addHeader("Origin","https://localhost:8443");assertThatThrownBy(()->controller().create(duplicateOrigin)).isInstanceOf(ResponseStatusException.class);
        var http=request();http.setSecure(false);http.addHeader("X-Forwarded-Proto","https");assertThatThrownBy(()->controller().create(http)).isInstanceOf(ResponseStatusException.class);
        var crossSite=request();crossSite.addHeader("Sec-Fetch-Site","cross-site");assertThatThrownBy(()->controller().create(crossSite)).isInstanceOf(ResponseStatusException.class);
        var issued=sessions.create(trace);var duplicate=request();duplicate.setCookies(new Cookie(GuestSessionService.COOKIE,issued.token()),new Cookie(GuestSessionService.COOKIE,issued.token()));
        assertThatThrownBy(()->controller().create(duplicate)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void introspectionRequiresIndependentServiceCredentialAndLogoutRevokesImmediately() {
        var issued=sessions.create(trace);var request=request();request.setCookies(new Cookie(GuestSessionService.COOKIE,issued.token()));
        assertThatThrownBy(()->controller().introspect(request,new GuestSessionController.Credential(issued.token()))).isInstanceOf(ResponseStatusException.class);
        request.addHeader("Authorization","Bearer "+key);assertThat(controller().introspect(request,new GuestSessionController.Credential(issued.token())).getBody()).isEqualTo(issued.principal());
        var duplicateAuthorization=request();duplicateAuthorization.addHeader("Authorization","Bearer "+key);duplicateAuthorization.addHeader("Authorization","Bearer "+key);
        assertThatThrownBy(()->controller().introspect(duplicateAuthorization,new GuestSessionController.Credential(issued.token()))).isInstanceOf(ResponseStatusException.class);
        var revoked=controller().revoke(request);assertThat(revoked.getStatusCode().value()).isEqualTo(204);assertThat(revoked.getHeaders().getFirst("Set-Cookie")).contains("Max-Age=0");
        assertThatThrownBy(()->controller().current(request)).isInstanceOf(ResponseStatusException.class);
    }
}
