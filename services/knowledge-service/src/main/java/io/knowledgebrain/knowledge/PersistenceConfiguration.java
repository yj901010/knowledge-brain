package io.knowledgebrain.knowledge;
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
@ConditionalOnProperty(name="brain.knowledge.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource knowledgeDataSource(@Value("${brain.knowledge.database.url}") String url,@Value("${brain.knowledge.database.password}") String password,@Value("${brain.knowledge.database.maximum-pool-size:10}") int size,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_knowledge");config.setPassword(password);config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("knowledge-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; } catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean ConceptStore conceptStore(DataSource knowledgeDataSource) { return new ConceptStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean AliasStore aliasStore(DataSource knowledgeDataSource) { return new AliasStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean ClaimStore claimStore(DataSource knowledgeDataSource) { return new ClaimStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean SourceStore sourceStore(DataSource knowledgeDataSource) { return new SourceStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean ExternalMappingStore externalMappingStore(DataSource knowledgeDataSource) { return new ExternalMappingStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean QualifierStore qualifierStore(DataSource knowledgeDataSource) { return new QualifierStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean CitationStore citationStore(DataSource knowledgeDataSource) { return new CitationStore(knowledgeDataSource,Clock.systemUTC()); }
    @Bean ContributionStore contributionStore(DataSource knowledgeDataSource,ConceptStore concepts){return new ContributionStore(knowledgeDataSource,concepts,Clock.systemUTC());}
    @Bean PublicRecordStore publicRecordStore(DataSource knowledgeDataSource){return new PublicRecordStore(knowledgeDataSource,Clock.systemUTC());}
    @Bean HealthIndicator knowledgeDatabaseHealthIndicator(DataSource knowledgeDataSource) {
        return ()->{ try { return Integer.valueOf(1).equals(new JdbcTemplate(knowledgeDataSource).queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); } catch(RuntimeException error) { return Health.down().build(); } };
    }
}
