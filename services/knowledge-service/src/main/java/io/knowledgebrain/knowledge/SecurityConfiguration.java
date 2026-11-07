package io.knowledgebrain.knowledge;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
class SecurityConfiguration {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.knowledge.api.enabled:false}") boolean enabled,@Value("${brain.knowledge.public.enabled:false}") boolean publicEnabled,@Value("${brain.knowledge.snapshot.enabled:false}") boolean snapshot,@Value("${brain.knowledge.activity-export.enabled:false}") boolean activityExport,@Value("${brain.knowledge.public-discovery.enabled:false}") boolean publicDiscovery) throws Exception {
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());
        if(enabled)http.csrf(c->c.ignoringRequestMatchers("/api/spaces/*/concepts","/api/spaces/*/concepts/**","/api/spaces/*/sources","/api/spaces/*/sources/**","/internal/knowledge/concept-check"));
        if(publicEnabled)http.csrf(c->c.ignoringRequestMatchers("/api/public/spaces/*/contributions","/api/public/spaces/*/contributions/**","/api/public/spaces/*/record-proposals","/api/public/spaces/*/record-proposals/**"));
        if(publicDiscovery)http.csrf(c->c.ignoringRequestMatchers("/internal/knowledge/public-check"));
        return http.authorizeHttpRequests(auth->{
            auth.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll();
            if(enabled)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/spaces/*/concept-references/*/learning-reference").permitAll();
            if(activityExport)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/knowledge/activity-events").permitAll();
            if(publicDiscovery)auth.requestMatchers("/internal/knowledge/public-snapshots","/internal/knowledge/public-check").permitAll();
            if(snapshot)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/internal/knowledge/brain-snapshots").permitAll();
            if(publicEnabled)auth.requestMatchers("/api/public/spaces/*/concepts","/api/public/spaces/*/concepts/**","/api/public/spaces/*/contributions","/api/public/spaces/*/contributions/**","/api/public/spaces/*/records","/api/public/spaces/*/records/**","/api/public/spaces/*/record-proposals","/api/public/spaces/*/record-proposals/**").permitAll();
            if(publicEnabled)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/api/public/spaces/*/explore").permitAll();
            
            if(enabled)auth.requestMatchers("/api/spaces/*/concepts","/api/spaces/*/concepts/**","/api/spaces/*/sources","/api/spaces/*/sources/**","/internal/knowledge/concept-check","/internal/knowledge/concept-check").permitAll();auth.anyRequest().denyAll();
        }).build();
    }
}
