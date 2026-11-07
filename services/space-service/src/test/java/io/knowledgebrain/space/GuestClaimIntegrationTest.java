package io.knowledgebrain.space;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="BRAIN_TEST_SPACE_DATABASE_URL",matches=".+")
class GuestClaimIntegrationTest {
 PersonalSpaceStore store;JdbcTemplate jdbc;UuidV7 ids=new UuidV7(Clock.systemUTC());
 @BeforeEach void setup(){var source=new PGSimpleDataSource();source.setURL(System.getenv("BRAIN_TEST_SPACE_DATABASE_URL"));source.setUser("brain_space");source.setPassword(System.getenv("BRAIN_TEST_SPACE_DATABASE_PASSWORD"));Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();store=new PersonalSpaceStore(source,Clock.systemUTC());jdbc=new JdbcTemplate(source);}
 GuestIdentityEvent guest(UUID id){return new GuestIdentityEvent(ids.next(),id,"c".repeat(32),new byte[32]);}
 GuestClaimEvent claim(UUID guest,UUID owner){return new GuestClaimEvent(ids.next(),guest,owner,"c".repeat(32),new byte[32]);}
 @Test void transferPreservesSpaceIdAndPrivateVisibilityAndRevokesGuestMembership(){var guest=ids.next();var owner=ids.next();var original=guest(guest);var created=store.provision(original);var event=claim(guest,owner);store.claim(event);store.claim(event);assertThat(store.canAccess(created.spaceId(),guest)).isFalse();assertThat(store.visible(created.spaceId(),owner).visibility()).isEqualTo("PRIVATE");assertThat(store.personal(owner).spaceId()).isEqualTo(created.spaceId());assertThat(store.provision(guest(guest)).spaceId()).isEqualTo(created.spaceId());assertThat(store.personal(guest)).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregateid=? AND type='PersonalSpaceClaimed'",Long.class,created.spaceId())).isEqualTo(1);}
 @Test void claimBeforeProvisionAndMultipleGuestSpacesPreserveAllSpaceIds(){var owner=ids.next();var guest=ids.next();store.claim(claim(guest,owner));var first=store.provision(guest(guest));assertThat(store.personal(owner).spaceId()).isEqualTo(first.spaceId());assertThat(store.canAccess(first.spaceId(),guest)).isFalse();var other=ids.next();var second=store.provision(guest(other));store.claim(claim(other,owner));assertThat(store.owned(owner)).extracting(PersonalSpaceStore.SpaceView::spaceId).containsExactlyInAnyOrder(first.spaceId(),second.spaceId());assertThat(store.personal(owner).spaceId()).isEqualTo(second.spaceId());}
 @Test void differentOwnerAndReusedEventFingerprintAreRejected(){var guest=ids.next();var owner=ids.next();var event=claim(guest,owner);store.claim(event);assertThatThrownBy(()->store.claim(claim(guest,ids.next()))).isInstanceOf(RuntimeException.class);byte[] altered=new byte[32];altered[0]=1;assertThatThrownBy(()->store.claim(new GuestClaimEvent(event.eventId(),guest,owner,event.traceId(),altered))).isInstanceOf(RuntimeException.class);assertThat(store.claimed(guest,ids.next())).isNull();}
}
