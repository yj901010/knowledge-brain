package io.knowledgebrain.integration;
import java.util.*;import jakarta.servlet.http.HttpServletRequest;import org.springframework.web.bind.annotation.*;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.http.*;import io.micrometer.core.instrument.MeterRegistry;
/** Narrow authenticated owner read. Never reads GitHub or Learning storage. */
@RestController @ConditionalOnProperty(name="brain.integration.api.enabled",havingValue="true")
final class GitHubLearningReferenceController {
 record Reference(UUID spaceId,UUID principalId,UUID repositoryId,UUID commitId,String repositoryFullName,String sha,String subject,String authorLogin,String committedAt,String importedAt,String url,String visibility,String sourceVisibility,long version){}
 private final GitHubStore store;private final GitHubController authority;private final MeterRegistry metrics;
 GitHubLearningReferenceController(GitHubStore store,GitHubController authority,MeterRegistry metrics){this.store=store;this.authority=authority;this.metrics=metrics;}
 @GetMapping("/api/spaces/{spaceId}/github/repositories/{repositoryId}/commits/{commitId}/reference") ResponseEntity<Reference> read(@PathVariable String spaceId,@PathVariable String repositoryId,@PathVariable String commitId,HttpServletRequest r){GitHubController.query(r,Set.of());UUID space=GitHubRules.id(spaceId),repo=GitHubRules.id(repositoryId),commit=GitHubRules.id(commitId);UUID actor=authority.authorize(space,r,false,GitHubController.trace(r));var value=store.commit(space,repo,commit);metrics.counter("brain.integration.github.references").increment();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Reference(space,actor,repo,commit,value.repositoryFullName(),value.sha(),value.subject(),value.authorLogin(),value.committedAt(),value.importedAt(),value.url(),"PRIVATE","PUBLIC",1));}
}
