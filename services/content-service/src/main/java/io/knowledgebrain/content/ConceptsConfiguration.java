package io.knowledgebrain.content;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.content.concepts.enabled",havingValue="true")
class ConceptsConfiguration {
 @Bean ConceptAvailabilityClient conceptAvailability(@Value("${brain.content.knowledge.origin}") String origin,@Value("${brain.content.knowledge.trust-file}") String trust,@Value("${brain.content.knowledge.authorization-key}") String key) throws Exception{return new ConceptAvailabilityClient(origin,trust,key);}
}
