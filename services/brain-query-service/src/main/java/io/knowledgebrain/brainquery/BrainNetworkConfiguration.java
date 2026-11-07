package io.knowledgebrain.brainquery;
import java.nio.file.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.query.network.enabled",havingValue="true")
class BrainNetworkConfiguration {
 @Bean BrainProjectionStore brainProjectionStore(DataSource source){return new BrainProjectionStore(source);}
 static BrainGraphStore graph(String host,String directory,String trust){try{return new BrainGraphStore(NoteGraphStore.open(host,Files.readString(Path.of(directory,"username")).strip(),Files.readString(Path.of(directory,"password")).strip(),trust,Files.readString(Path.of(directory,"password")).strip()));}catch(Exception e){throw new IllegalStateException("Network graph unavailable");}}
 @Bean BrainProjectionConsumer brainProjectionConsumer(BrainProjectionStore store,@Qualifier("projectionIndex")NoteSearchClient search,MeterRegistry metrics,@Value("${brain.query.graph.host}")String host,@Value("${brain.query.kafka.bootstrap-servers}")String bootstrap)throws Exception{String trust=GraphConfiguration.truststore("/graph-projector");var options=GraphConfiguration.kafka(bootstrap,"/network-kafka");options.put("group.id",BrainProjectionConsumer.GROUP);return new BrainProjectionConsumer(store,search,()->graph(host,"/graph-projector",trust),options,metrics);}
 @Bean HealthIndicator brainNetworkProjectionHealthIndicator(BrainProjectionConsumer consumer){return ()->consumer.isReady()?Health.up().build():Health.down().build();}
}
