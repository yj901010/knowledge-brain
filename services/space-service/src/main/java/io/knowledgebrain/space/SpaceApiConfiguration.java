package io.knowledgebrain.space;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.space.api.enabled",havingValue="true")
class SpaceApiConfiguration {
    @Bean IdentityPrincipalClient identityPrincipalClient(@Value("${brain.space.identity.origin}") String origin,
            @Value("${brain.space.identity.trust-file}") String trust,@Value("${brain.space.identity.introspection-key}") String key) throws Exception {
        return new IdentityPrincipalClient(origin,trust,key);
    }
}
