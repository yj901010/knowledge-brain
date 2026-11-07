package io.knowledgebrain.brainquery;
import java.nio.file.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.query.public.enabled",havingValue="true")
class PublicDiscoveryConfiguration {
 @Bean(destroyMethod="close") PublicSearchCache publicSearchCache(MeterRegistry metrics,@Value("${brain.query.public.cache.enabled:false}")boolean enabled,@Value("${brain.query.public.cache.host:brain-valkey.brain-data.svc}")String host,@Value("${brain.query.public.cache.port:6379}")int port,@Value("${brain.query.public.cache.directory:/public-cache}")String directory)throws Exception{return new PublicSearchCache(enabled?new ValkeyPublicSearchTransport(host,port,Path.of(directory)):null,metrics);}
 @Bean PublicProjectionStore publicProjectionStore(DataSource source){return new PublicProjectionStore(source);}
 static PublicGraphStore graph(String host,String directory,String trust){try{return new PublicGraphStore(NoteGraphStore.open(host,Files.readString(Path.of(directory,"username")).strip(),Files.readString(Path.of(directory,"password")).strip(),trust,Files.readString(Path.of(directory,"password")).strip()));}catch(Exception e){throw new IllegalStateException("Public graph unavailable");}}
 @Bean PublicProjectionConsumer publicProjectionConsumer(PublicProjectionStore store,@Qualifier("projectionIndex")NoteSearchClient search,MeterRegistry metrics,@Value("${brain.query.graph.host}")String host,@Value("${brain.query.kafka.bootstrap-servers}")String bootstrap)throws Exception{String trust=GraphConfiguration.truststore("/graph-projector");var options=GraphConfiguration.kafka(bootstrap,"/public-kafka");options.put("group.id",PublicProjection.GROUP);return new PublicProjectionConsumer(store,search,()->graph(host,"/graph-projector",trust),options,metrics);}
 @Bean PublicCanonicalClient publicCanonicalClient(@Value("${brain.query.public.knowledge-origin}")String origin,@Value("${brain.query.space.trust-file}")String trust,@Value("${brain.query.public.check-key}")String key)throws Exception{return new PublicCanonicalClient(origin,trust,key);}
 // Public rebuild pauses only this feature, not the whole service/private read models.
 @Bean HealthIndicator publicDiscoveryHealthIndicator(PublicProjectionConsumer consumer,PublicProjectionStore store){return ()->!store.available()?Health.up().withDetail("publicDiscovery","REBUILDING").build():consumer.isReady()?Health.up().build():Health.down().build();}
}
