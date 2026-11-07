package io.knowledgebrain.content;
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
@ConditionalOnProperty(name="brain.content.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource contentDataSource(@Value("${brain.content.database.url}") String url,@Value("${brain.content.database.password}") String password,@Value("${brain.content.database.maximum-pool-size:10}") int size,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_content");config.setPassword(password);config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("content-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; } catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean RecordConceptStore recordConceptStore(DataSource contentDataSource) { return new RecordConceptStore(contentDataSource,Clock.systemUTC()); }
    @Bean DecisionStore decisionStore(DataSource contentDataSource) { return new DecisionStore(contentDataSource,Clock.systemUTC()); }
    @Bean QuestionStore questionStore(DataSource contentDataSource) { return new QuestionStore(contentDataSource,Clock.systemUTC()); }
    @Bean BookmarkStore bookmarkStore(DataSource contentDataSource) { return new BookmarkStore(contentDataSource,Clock.systemUTC()); }
    @Bean NoteStore noteStore(DataSource contentDataSource) { return new NoteStore(contentDataSource,Clock.systemUTC()); }
    @Bean HealthIndicator contentDatabaseHealthIndicator(DataSource contentDataSource) {
        return ()->{ try { return Integer.valueOf(1).equals(new JdbcTemplate(contentDataSource).queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); } catch(RuntimeException error) { return Health.down().build(); } };
    }
}
