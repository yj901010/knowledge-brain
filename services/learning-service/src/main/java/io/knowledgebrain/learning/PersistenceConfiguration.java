package io.knowledgebrain.learning;
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
@ConditionalOnProperty(name="brain.learning.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource learningDataSource(@Value("${brain.learning.database.url}") String url,@Value("${brain.learning.database.password}") String password,@Value("${brain.learning.database.maximum-pool-size:10}") int size,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_learning");config.setPassword(password);config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("learning-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; } catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean CourseProgressShareStore courseProgressShareStore(DataSource learningDataSource,CourseProgressStore progress){return new CourseProgressShareStore(learningDataSource,progress,Clock.systemUTC());}
    @Bean CourseProgressStore courseProgressStore(DataSource learningDataSource){return new CourseProgressStore(learningDataSource,Clock.systemUTC());}
    @Bean GroupRoadmapStore groupRoadmapStore(DataSource learningDataSource,PublicRoadmapStore published) { return new GroupRoadmapStore(learningDataSource,published,Clock.systemUTC()); }
    @Bean RoadmapAdoptionStore roadmapAdoptionStore(DataSource learningDataSource,PublicRoadmapStore published,RoadmapStore personal) { return new RoadmapAdoptionStore(learningDataSource,published,personal,Clock.systemUTC()); }
    @Bean RoadmapRatingStore roadmapRatingStore(DataSource learningDataSource,PublicRoadmapStore roadmaps) { return new RoadmapRatingStore(learningDataSource,roadmaps,Clock.systemUTC()); }
    @Bean PublicRoadmapStore publicRoadmapStore(DataSource learningDataSource) { return new PublicRoadmapStore(learningDataSource,Clock.systemUTC()); }
    @Bean RoadmapStore roadmapStore(DataSource learningDataSource) { return new RoadmapStore(learningDataSource,Clock.systemUTC()); }
    @Bean LearningStudyStore learningStudyStore(DataSource learningDataSource,RoadmapStore roadmaps) { return new LearningStudyStore(learningDataSource,Clock.systemUTC(),roadmaps); }
    @Bean RoadmapGuidanceStore roadmapGuidanceStore(DataSource learningDataSource) { return new RoadmapGuidanceStore(learningDataSource); }
    @Bean GitHubEvidenceStore githubEvidenceStore(DataSource learningDataSource,RoadmapStore roadmaps){return new GitHubEvidenceStore(learningDataSource,Clock.systemUTC(),roadmaps);}
    @Bean ConceptEvidenceStore conceptEvidenceStore(DataSource learningDataSource,RoadmapStore roadmaps) { return new ConceptEvidenceStore(learningDataSource,Clock.systemUTC(),roadmaps); }
    @Bean MediaEvidenceStore mediaEvidenceStore(DataSource learningDataSource,RoadmapStore roadmaps) { return new MediaEvidenceStore(learningDataSource,Clock.systemUTC(),roadmaps); }
    @Bean EvidenceSessionStore evidenceSessionStore(DataSource learningDataSource,RoadmapStore roadmaps,LearningStudyStore sessions) { return new EvidenceSessionStore(learningDataSource,Clock.systemUTC(),roadmaps,sessions); }
    @Bean LearningEvidenceStore learningEvidenceStore(DataSource learningDataSource,RoadmapStore roadmaps) { return new LearningEvidenceStore(learningDataSource,Clock.systemUTC(),roadmaps); }
    @Bean HealthIndicator learningDatabaseHealthIndicator(DataSource learningDataSource) {
        return ()->{ try { return Integer.valueOf(1).equals(new JdbcTemplate(learningDataSource).queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); } catch(RuntimeException error) { return Health.down().build(); } };
    }
}
