package io.knowledgebrain.identity;

import java.time.Clock;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.zaxxer.hikari.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.health.contributor.*;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name="brain.identity.persistence.enabled", havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource identityDataSource(@Value("${brain.identity.database.url}") String url,
            @Value("${brain.identity.database.password}") String password,@Value("${brain.identity.database.maximum-pool-size:10}") int poolSize,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_identity");config.setPassword(password);
        config.setMaximumPoolSize(poolSize);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setPoolName("identity-owner");
        config.setConnectionInitSql("SET statement_timeout = '10s'");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);
        try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; }
        catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean GuestRegistrationStore guestRegistrationStore(DataSource identityDataSource) {
        return new GuestRegistrationStore(identityDataSource, Clock.systemUTC());
    }
    @Bean GuestSessionService guestSessionService(GuestRegistrationStore registrations,DataSource identityDataSource,org.springframework.beans.factory.ObjectProvider<OidcLoginService> registered) {
        var sessions=new GuestSessionService(registrations,identityDataSource,Clock.systemUTC());registered.ifAvailable(sessions::registered);return sessions;
    }
    @Bean HealthIndicator identityDatabaseHealthIndicator(DataSource identityDataSource) {
        var jdbc=new JdbcTemplate(identityDataSource);
        return ()->{try { return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); }
            catch(RuntimeException error) { return Health.down().build(); }};
    }
}
