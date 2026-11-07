package io.knowledgebrain.content;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.content.attachments.enabled",havingValue="true")
class AttachmentsConfiguration {
 @Bean MediaAvailabilityClient mediaAvailability(@Value("${brain.content.media.origin}") String origin,@Value("${brain.content.media.trust-file}") String trust,@Value("${brain.content.media.authorization-key}") String key) throws Exception{return new MediaAvailabilityClient(origin,trust,key);}
}
