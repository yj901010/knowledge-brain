package io.knowledgebrain.media;
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
@ConditionalOnProperty(name="brain.media.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource mediaDataSource(@Value("${brain.media.database.url}") String url,@Value("${brain.media.database.password}") String password,@Value("${brain.media.database.maximum-pool-size:10}") int size,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_media");config.setPassword(password);config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("media-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; } catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean MediaStore mediaStore(DataSource mediaDataSource) { return new MediaStore(mediaDataSource,Clock.systemUTC()); }
    @Bean HealthIndicator mediaDatabaseHealthIndicator(DataSource mediaDataSource) {
        return ()->{ try { return Integer.valueOf(1).equals(new JdbcTemplate(mediaDataSource).queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); } catch(RuntimeException error) { return Health.down().build(); } };
    }
}
