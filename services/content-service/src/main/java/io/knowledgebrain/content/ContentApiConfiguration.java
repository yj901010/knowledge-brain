package io.knowledgebrain.content;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.content.api.enabled",havingValue="true")
class ContentApiConfiguration {
    @Bean SpaceAuthorizationClient spaceAuthorizationClient(@Value("${brain.content.space.origin}") String origin,@Value("${brain.content.space.trust-file}") String trust,@Value("${brain.content.space.authorization-key}") String key) throws Exception { return new SpaceAuthorizationClient(origin,trust,key); }
}
