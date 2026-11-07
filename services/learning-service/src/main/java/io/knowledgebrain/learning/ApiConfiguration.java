package io.knowledgebrain.learning;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="brain.learning.api.enabled",havingValue="true")
final class ApiConfiguration {
 @Bean CourseContextClient courseContextClient(@Value("${brain.learning.collaboration.origin:https://collaboration-service.brain-apps.svc:8080}")String origin,@Value("${brain.learning.space.trust-file}")String trust)throws Exception{return new CourseContextClient(origin,trust);}
 @Bean ConceptReferenceClient learningConceptReferences(@Value("${brain.learning.knowledge.origin:https://knowledge-service.brain-apps.svc:8080}")String origin,@Value("${brain.learning.space.trust-file}")String trust)throws Exception{return new ConceptReferenceClient(origin,trust);}
 @Bean MediaReferenceClient learningMediaReferences(@Value("${brain.learning.media.origin:https://media-service.brain-apps.svc:8080}")String origin,@Value("${brain.learning.space.trust-file}")String trust)throws Exception{return new MediaReferenceClient(origin,trust);}
 @Bean GitHubReferenceClient learningGithubReferences(@Value("${brain.learning.integration.origin:https://integration-service.brain-apps.svc:8080}")String origin,@Value("${brain.learning.space.trust-file}")String trust)throws Exception{return new GitHubReferenceClient(origin,trust);}
 @Bean NoteReferenceClient learningNoteReferences(@Value("${brain.learning.content.origin:https://content-service.brain-apps.svc:8080}")String origin,@Value("${brain.learning.space.trust-file}")String trust)throws Exception{return new NoteReferenceClient(origin,trust);}
 @Bean SpaceAuthorizationClient learningSpaceAuthority(@Value("${brain.learning.space.origin}")String origin,@Value("${brain.learning.space.trust-file}")String trust,@Value("${brain.learning.space.authorization-key}")String key)throws Exception{return new SpaceAuthorizationClient(origin,trust,key);}
}
