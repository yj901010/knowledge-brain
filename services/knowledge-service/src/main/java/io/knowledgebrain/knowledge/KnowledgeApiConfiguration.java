package io.knowledgebrain.knowledge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.knowledge.api.enabled",havingValue="true")
class KnowledgeApiConfiguration {
    @Bean SpaceAuthorizationClient spaceAuthorizationClient(@Value("${brain.knowledge.space.origin}") String origin,@Value("${brain.knowledge.space.trust-file}") String trust,@Value("${brain.knowledge.space.authorization-key}") String key) throws Exception { return new SpaceAuthorizationClient(origin,trust,key); }
}
