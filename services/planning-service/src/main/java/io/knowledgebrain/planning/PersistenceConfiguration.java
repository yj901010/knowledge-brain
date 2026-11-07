package io.knowledgebrain.planning;
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
@ConditionalOnProperty(name="brain.planning.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource planningDataSource(@Value("${brain.planning.database.url}") String url,@Value("${brain.planning.database.password}") String password,@Value("${brain.planning.database.maximum-pool-size:10}") int size,MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_planning");config.setPassword(password);config.setMaximumPoolSize(size);config.setMinimumIdle(1);config.setConnectionTimeout(10000);config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("planning-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; } catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean ReminderDeliveryStore reminderDeliveries(DataSource planningDataSource){return new ReminderDeliveryStore(planningDataSource,Clock.systemUTC());}
    @Bean @ConditionalOnProperty(name="brain.planning.dispatch.enabled",havingValue="true") ReminderDispatchWorker dispatcher(ReminderDeliveryStore store,MeterRegistry metrics){return new ReminderDispatchWorker(store,metrics);}
    @Bean @ConditionalOnProperty(name="brain.planning.dispatch.enabled",havingValue="true") HealthIndicator planningDispatchHealthIndicator(ReminderDispatchWorker worker){return ()->worker.ready()?Health.up().build():Health.down().build();}
    @Bean ReminderStore reminders(DataSource planningDataSource){return new ReminderStore(planningDataSource,Clock.systemUTC());}
    @Bean RecurrenceStore recurrences(DataSource planningDataSource){return new RecurrenceStore(planningDataSource,Clock.systemUTC());}
    @Bean ScheduleStore schedules(DataSource planningDataSource){return new ScheduleStore(planningDataSource,Clock.systemUTC());}
    @Bean TaskStore tasks(DataSource planningDataSource){return new TaskStore(planningDataSource,Clock.systemUTC());}
    @Bean GoalStore goals(DataSource planningDataSource){return new GoalStore(planningDataSource,Clock.systemUTC());}
    @Bean HealthIndicator planningDatabaseHealthIndicator(DataSource planningDataSource) {
        return ()->{ try { return Integer.valueOf(1).equals(new JdbcTemplate(planningDataSource).queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); } catch(RuntimeException error) { return Health.down().build(); } };
    }
}
