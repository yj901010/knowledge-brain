package io.knowledgebrain.content;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
class SecurityConfiguration {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.content.api.enabled:false}") boolean enabled,@Value("${brain.content.snapshot.enabled:false}") boolean snapshot,@Value("${brain.content.activity-export.enabled:false}") boolean activityExport) throws Exception {
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());
        if(enabled)http.csrf(c->c.ignoringRequestMatchers("/api/spaces/*/record-concepts/**","/api/spaces/*/decisions","/api/spaces/*/decisions/**","/api/spaces/*/questions","/api/spaces/*/questions/**","/api/spaces/*/bookmarks","/api/spaces/*/bookmarks/**","/api/spaces/*/notes","/api/spaces/*/notes/**","/api/spaces/*/daily-notes","/api/spaces/*/daily-notes/**"));
        return http.authorizeHttpRequests(auth->{
            auth.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll();
            if(enabled){String b="/api/spaces/{spaceId}/bookmarks";auth.requestMatchers(org.springframework.http.HttpMethod.GET,b,b+"/{id}",b+"/{id}/revisions").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.POST,b).permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.PUT,b+"/{id}").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.DELETE,b+"/{id}").permitAll();}
            if(enabled){String b="/api/spaces/{spaceId}/questions";auth.requestMatchers(org.springframework.http.HttpMethod.GET,b,b+"/{id}",b+"/{id}/revisions").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.POST,b).permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.PUT,b+"/{id}").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.DELETE,b+"/{id}").permitAll();}
            if(enabled){String b="/api/spaces/{spaceId}/decisions";auth.requestMatchers(org.springframework.http.HttpMethod.GET,b,b+"/{id}",b+"/{id}/revisions").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.POST,b).permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.PUT,b+"/{id}").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.DELETE,b+"/{id}").permitAll();}
            if(enabled){String b="/api/spaces/{spaceId}/record-concepts/{kind}/{id}";auth.requestMatchers(org.springframework.http.HttpMethod.GET,b,b+"/revisions","/api/spaces/{spaceId}/concept-references/{conceptId}/records").permitAll();auth.requestMatchers(org.springframework.http.HttpMethod.PUT,b).permitAll();}
            if(activityExport)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/content/activity-events").permitAll();
            if(snapshot)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/content/note-snapshots","/internal/content/note-link-snapshots","/internal/content/brain-snapshots").permitAll();
            if(enabled)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/spaces/*/concept-references/*/notes","/api/spaces/*/tags/*/notes","/api/spaces/*/property-definitions/*/notes").permitAll();
            if(enabled)auth.requestMatchers("/api/spaces/*/notes","/api/spaces/*/notes/**","/api/spaces/*/daily-notes","/api/spaces/*/daily-notes/**","/api/spaces/*/tags","/api/spaces/*/property-definitions").permitAll();auth.anyRequest().denyAll();
        }).build();
    }
}
