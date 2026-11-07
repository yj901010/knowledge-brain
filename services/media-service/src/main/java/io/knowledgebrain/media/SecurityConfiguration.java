package io.knowledgebrain.media;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
class SecurityConfiguration {
 @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.media.api.enabled:false}") boolean enabled) throws Exception{http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());if(enabled)http.csrf(c->c.ignoringRequestMatchers("/api/spaces/*/media","/api/spaces/*/media/**","/internal/media/attachment-check","/internal/media/domain-files/*"));return http.authorizeHttpRequests(auth->{auth.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll();if(enabled)auth.requestMatchers("/api/spaces/*/media","/api/spaces/*/media/**","/internal/media/attachment-check","/internal/media/domain-files/*").permitAll();auth.anyRequest().denyAll();}).build();}
}
