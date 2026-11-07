package io.knowledgebrain.space;

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
@ConditionalOnProperty(name="brain.space.persistence.enabled",havingValue="true")
class PersistenceConfiguration {
    @Bean(destroyMethod="close") HikariDataSource spaceDataSource(@Value("${brain.space.database.url}") String url,
            @Value("${brain.space.database.password}") String password,@Value("${brain.space.database.maximum-pool-size:10}") int poolSize,
            MeterRegistry metrics) {
        var config=new HikariConfig();config.setJdbcUrl(url);config.setUsername("brain_space");config.setPassword(password);
        config.setMaximumPoolSize(poolSize);config.setMinimumIdle(1);config.setConnectionTimeout(10000);
        config.setConnectionInitSql("SET statement_timeout = '10s'");config.setPoolName("space-owner");config.setMetricRegistry(metrics);
        var source=new HikariDataSource(config);
        try { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();return source; }
        catch(RuntimeException error) { source.close();throw error; }
    }
    @Bean GroupProgressPolicyStore groupProgressPolicyStore(DataSource spaceDataSource){return new GroupProgressPolicyStore(spaceDataSource,Clock.systemUTC());}
    @Bean @ConditionalOnProperty(name="brain.space.group-membership.enabled",havingValue="true")
    GroupMembershipStore groupMembershipStore(DataSource spaceDataSource,@Value("${brain.space.group-membership.invitation-key}")String key){return new GroupMembershipStore(spaceDataSource,Clock.systemUTC(),new GroupInvitationCodes(key));}
    @Bean GroupSpaceStore groupSpaceStore(DataSource spaceDataSource) { return new GroupSpaceStore(spaceDataSource,Clock.systemUTC()); }
    @Bean PersonalSpaceStore personalSpaceStore(DataSource spaceDataSource) { return new PersonalSpaceStore(spaceDataSource,Clock.systemUTC()); }
    @Bean
    @ConditionalOnProperty(name="brain.space.consumer.enabled",havingValue="true")
    GuestSpaceConsumer guestSpaceConsumer(PersonalSpaceStore store,MeterRegistry metrics,
            @Value("${brain.space.kafka.bootstrap-servers}") String bootstrap,@Value("${brain.space.kafka.credentials-directory:/kafka}") String directory) throws Exception {
        Properties options=new Properties();
        options.put("bootstrap.servers",bootstrap);options.put("group.id","space-guest-provisioning-v1");
        options.put("enable.auto.commit","false");options.put("auto.offset.reset","earliest");options.put("max.poll.records","10");
        options.put("key.deserializer",StringDeserializer.class.getName());options.put("value.deserializer",ByteArrayDeserializer.class.getName());
        options.put("security.protocol","SSL");options.put("ssl.endpoint.identification.algorithm","HTTPS");
        options.put("ssl.truststore.type","PEM");options.put("ssl.truststore.certificates",Files.readString(Path.of(directory,"ca.crt")));
        options.put("ssl.keystore.type","PEM");options.put("ssl.keystore.certificate.chain",Files.readString(Path.of(directory,"user.crt")));
        options.put("ssl.keystore.key",Files.readString(Path.of(directory,"user.key")));
        return new GuestSpaceConsumer(store,options,metrics);
    }
    @Bean
    @ConditionalOnProperty(name="brain.space.consumer.enabled",havingValue="true")
    HealthIndicator spaceConsumerHealthIndicator(GuestSpaceConsumer consumer,DataSource spaceDataSource) {
        var jdbc=new JdbcTemplate(spaceDataSource);
        return ()->{
            if(!consumer.isReady())return Health.down().build();
            try { return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1",Integer.class))?Health.up().build():Health.down().build(); }
            catch(RuntimeException error) { return Health.down().build(); }
        };
    }
}
