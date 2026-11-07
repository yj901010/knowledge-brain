package io.knowledgebrain.brainquery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
@Configuration
class SecurityConfiguration {
 @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.query.api.enabled:false}") boolean enabled) throws Exception {
  http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());
  return http.authorizeHttpRequests(auth->{auth.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll();if(enabled)auth.requestMatchers(HttpMethod.GET,"/api/spaces/{spaceId}/search/notes","/api/spaces/{spaceId}/graph/notes","/api/spaces/{spaceId}/search/network","/api/spaces/{spaceId}/graph/network","/api/public/spaces/{spaceId}/search","/api/public/spaces/{spaceId}/graph").permitAll();auth.anyRequest().denyAll();}).build();
 }
}
