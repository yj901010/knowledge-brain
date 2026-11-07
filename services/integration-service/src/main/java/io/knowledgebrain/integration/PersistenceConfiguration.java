package io.knowledgebrain.integration;
import com.zaxxer.hikari.*;
import java.time.Clock;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.integration.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource integrationDataSource(@Value("${brain.integration.database.url}") String url,@Value("${brain.integration.database.password}") String password,@Value("${brain.integration.database.maximum-pool-size:10}") int size,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_integration");config.setPassword(password);config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("integration-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; } catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean GitHubStore repositories(DataSource integrationDataSource){return new GitHubStore(integrationDataSource,Clock.systemUTC());}
    @Bean GitHubWorkStore workItems(DataSource integrationDataSource,GitHubStore repositories){return new GitHubWorkStore(integrationDataSource,Clock.systemUTC(),repositories);}
    @Bean HealthIndicator integrationDatabaseHealthIndicator(DataSource integrationDataSource){return ()->{try{return Integer.valueOf(1).equals(new JdbcTemplate(integrationDataSource).queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build();}catch(RuntimeException e){return Health.down().build();}};}
}
