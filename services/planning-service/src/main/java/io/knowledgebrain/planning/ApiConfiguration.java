package io.knowledgebrain.planning;
import org.springframework.context.annotation.*;import org.springframework.beans.factory.annotation.Value;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Configuration(proxyBeanMethods=false) @ConditionalOnProperty(name="brain.planning.api.enabled",havingValue="true")
class ApiConfiguration {@Bean SpaceAuthorizationClient planningAuthority(@Value("${brain.planning.space.origin}")String origin,@Value("${brain.planning.space.trust-file}")String trust,@Value("${brain.planning.space.authorization-key}")String key)throws Exception{return new SpaceAuthorizationClient(origin,trust,key);}}
