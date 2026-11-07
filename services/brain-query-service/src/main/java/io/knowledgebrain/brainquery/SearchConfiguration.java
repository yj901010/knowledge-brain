package io.knowledgebrain.brainquery;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.query.api.enabled",havingValue="true")
class SearchConfiguration {
 @Bean NoteSearchClient searchReader(@Value("${brain.query.search.origin}") String origin,@Value("${brain.query.search.reader-directory:/search-reader}") String directory) throws Exception{return new NoteSearchClient(origin,Path.of(directory));}
 @Bean SpaceAuthorizationClient spaceAuthorization(@Value("${brain.query.space.origin}") String origin,@Value("${brain.query.space.trust-file}") String trust,@Value("${brain.query.space.authorization-key}") String key) throws Exception{return new SpaceAuthorizationClient(origin,trust,key);}
}
