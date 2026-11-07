package io.knowledgebrain.brainquery;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.query.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource queryDataSource(@Value("${brain.query.database.url}") String url,
            @Value("${brain.query.database.password}") String password,@Value("${brain.query.database.maximum-pool-size:10}") int poolSize,
            MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_brainquery");config.setPassword(password);
        config.setMaximumPoolSize(poolSize);config.setMinimumIdle(1);config.setConnectionTimeout(10000);
        config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("brain-query-projection");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);
        try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; }
        catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean NoteProjectionStore projectionStore(DataSource queryDataSource) { return new NoteProjectionStore(queryDataSource); }
    @Bean NoteSearchClient projectionIndex(@Value("${brain.query.search.origin}") String origin,@Value("${brain.query.search.projector-directory:/search-projector}") String directory) throws Exception { return new NoteSearchClient(origin,Path.of(directory)); }
    @Bean
    @ConditionalOnProperty(name="brain.query.consumer.enabled",havingValue="true")
    NoteProjectionConsumer noteProjectionConsumer(NoteProjectionStore store,@org.springframework.beans.factory.annotation.Qualifier("projectionIndex") NoteSearchClient index,MeterRegistry metrics,
            @Value("${brain.query.kafka.bootstrap-servers}") String bootstrap,@Value("${brain.query.kafka.credentials-directory:/kafka}") String directory) throws Exception {
        Properties options=new Properties();
        options.put("bootstrap.servers",bootstrap);options.put("group.id","brain-query-note-search-v1");
        options.put("enable.auto.commit","false");options.put("auto.offset.reset","none");options.put("max.poll.records","10");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of(directory,"ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of(directory,"user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of(directory,"user.key")));
        return new NoteProjectionConsumer(store,index,options,metrics);
    }
    @Bean
    @ConditionalOnProperty(name="brain.query.consumer.enabled",havingValue="true")
    HealthIndicator noteProjectionHealthIndicator(NoteProjectionConsumer consumer,DataSource queryDataSource) {
        var jdbc=new JdbcTemplate(queryDataSource);
        return ()->{
            if(!consumer.isReady())return Health.down().build();
            try { return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); }
            catch(RuntimeException error) { return Health.down().build(); }
        };
    }
}
