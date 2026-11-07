package io.knowledgebrain.media;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.media.api.enabled",havingValue="true")
class MediaConfiguration {
 @Bean DomainMediaStore domainMediaStore(javax.sql.DataSource mediaDataSource,MediaStore media){return new DomainMediaStore(mediaDataSource,media);}
 @Bean SpaceAuthorizationClient mediaAuthority(@Value("${brain.media.space.origin}") String origin,@Value("${brain.media.space.trust-file}") String trust,@Value("${brain.media.space.authorization-key}") String key) throws Exception{return new SpaceAuthorizationClient(origin,trust,key);}
 @Bean ObjectStorage objectStorage(@Value("${brain.media.storage.origin}") String origin,@Value("${brain.media.storage.public-origin}") String publicOrigin,@Value("${brain.media.storage.access-key}") String access,@Value("${brain.media.storage.secret-key}") String secret,@Value("${brain.media.storage.trust-file}") String trust) throws Exception{return new MinioStorage(origin,publicOrigin,access,secret,trust,Clock.systemUTC());}
 @Bean HealthIndicator mediaStorageHealthIndicator(ObjectStorage storage){return ()->storage.ready()?Health.up().build():Health.down().build();}
}
