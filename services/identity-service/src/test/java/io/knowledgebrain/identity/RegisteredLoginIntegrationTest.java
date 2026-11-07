package io.knowledgebrain.identity;

import java.time.*;
import java.net.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BRAIN_TEST_DATABASE_URL",matches=".+")
class RegisteredLoginIntegrationTest {
    PGSimpleDataSource source;JdbcTemplate jdbc;GuestSessionService guests;OidcLoginService login;FakeProvider provider;
    static class FakeProvider extends OidcProvider {
        String subject=UUID.randomUUID().toString();boolean active=true;int exchanges,refreshes;
        FakeProvider(){super("https://localhost:8445/realms/brain-users","brain-web","https://localhost:8443/api/identity/login/callback");}
        @Override Tokens exchange(String code,String verifier,String nonce){exchanges++;assertThat(verifier).hasSize(43);assertThat(nonce).hasSize(43);return new Tokens("secret-access","secret-refresh",Instant.now().plusSeconds(300),Instant.now().plusSeconds(1800),subject);}
        @Override void active(String token,String subject){if(!active)throw GuestSessionService.unauthorized();}
        @Override Tokens refresh(String refresh,String subject){refreshes++;return exchange("refresh","A".repeat(43),"B".repeat(43));}
        @Override void revoke(String token){}
    }
    @BeforeEach void setup(){source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_DATABASE_URL"));source.setUser("brain_identity");source.setPassword(System.getenv("BRAIN_TEST_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(source);guests=new GuestSessionService(new GuestRegistrationStore(source,Clock.systemUTC()),source,Clock.systemUTC());provider=new FakeProvider();login=new OidcLoginService(source,Clock.systemUTC(),new TokenCipher("A".repeat(43)),provider);guests.registered(login);}
    String state(OidcLoginService.Started start){return Arrays.stream(URI.create(start.authorizationUrl()).getRawQuery().split("&")).filter(v->v.startsWith("state=")).findFirst().orElseThrow().substring(6);}
    GuestSessionService.Issued claim(GuestSessionService.Issued guest){var start=login.start(guest.token(),"c".repeat(32));return login.callback(state(start),start.binding(),guest.token(),"code");}
    @Test void claimRotatesCookieRetiresGuestAndAtomicallyEmitsEventWithEncryptedCredentials(){var guest=guests.create("c".repeat(32));var account=claim(guest);assertThat(account.token()).isNotEqualTo(guest.token());assertThat(guests.resolve(account.token()).kind()).isEqualTo("REGISTERED");assertThatThrownBy(()->guests.resolve(guest.token())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(jdbc.queryForObject("SELECT claimed_by FROM guest_identity WHERE principal_id=?",UUID.class,guest.principal().principalId())).isEqualTo(account.principal().principalId());assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND type='GuestSpaceClaimRequested'",Long.class,account.principal().principalId())).isEqualTo(1);var cipher=jdbc.queryForObject("SELECT access_token FROM registered_session WHERE credential_hash=?",byte[].class,GuestSessionService.hash(account.token()));assertThat(new String(cipher,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("secret-access");assertThat(login.current(account.token()).claimGuestId()).isEqualTo(guest.principal().principalId());}
    @Test void sameKeycloakSubjectUsesStablePrincipalAcrossDifferentGuestSpaces(){var first=claim(guests.create("c".repeat(32)));var second=claim(guests.create("c".repeat(32)));assertThat(second.principal().principalId()).isEqualTo(first.principal().principalId());assertThat(login.current(second.token()).claimGuestId()).isNotEqualTo(login.current(first.token()).claimGuestId());}
    @Test void wrongBrowserOrGuestCannotConsumeFlowAndSuccessfulCallbackCannotReplay(){var guest=guests.create("c".repeat(32));var other=guests.create("c".repeat(32));var start=login.start(guest.token(),"c".repeat(32));String state=state(start);assertThatThrownBy(()->login.callback(state,"A".repeat(43),guest.token(),"code")).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->login.callback(state,start.binding(),other.token(),"code")).isInstanceOf(RuntimeException.class);assertThat(provider.exchanges).isZero();login.callback(state,start.binding(),guest.token(),"code");assertThatThrownBy(()->login.callback(state,start.binding(),guest.token(),"code")).isInstanceOf(RuntimeException.class);assertThat(provider.exchanges).isEqualTo(1);}
    @Test void expiredFlowAndGuestRevokedDuringLoginDoNotCreateAccount(){var guest=guests.create("c".repeat(32));var start=login.start(guest.token(),"c".repeat(32));jdbc.update("UPDATE oidc_login_flow SET expires_at=? WHERE state_hash=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),GuestSessionService.hash(state(start)));assertThatThrownBy(()->login.callback(state(start),start.binding(),guest.token(),"code")).isInstanceOf(RuntimeException.class);assertThat(provider.exchanges).isZero();var next=login.start(guest.token(),"c".repeat(32));guests.revoke(guest.token());assertThatThrownBy(()->login.callback(state(next),next.binding(),guest.token(),"code")).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM registered_account WHERE subject=?",Long.class,provider.subject)).isZero();}
    @Test void freshIntrospectionAndLocalRevocationRejectOldSession(){var account=claim(guests.create("c".repeat(32)));provider.active=false;assertThatThrownBy(()->login.current(account.token())).isInstanceOf(RuntimeException.class);provider.active=true;jdbc.update("UPDATE registered_session SET access_expires_at=? WHERE credential_hash=?",java.sql.Timestamp.from(Instant.now()),GuestSessionService.hash(account.token()));login.current(account.token());assertThat(provider.refreshes).isEqualTo(1);guests.revoke(account.token());assertThatThrownBy(()->guests.resolve(account.token())).isInstanceOf(RuntimeException.class);}
}
