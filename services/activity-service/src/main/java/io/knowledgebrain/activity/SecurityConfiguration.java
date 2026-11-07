package io.knowledgebrain.activity;
import org.springframework.context.annotation.*;import org.springframework.security.config.annotation.web.builders.HttpSecurity;import org.springframework.security.web.SecurityFilterChain;import org.springframework.http.HttpMethod;
@Configuration class SecurityConfiguration {
 @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,@org.springframework.beans.factory.annotation.Value("${brain.activity.api.enabled:false}") boolean enabled)throws Exception{return http.sessionManagement(s->s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS)).requestCache(c->c.disable()).authorizeHttpRequests(a->{a.requestMatchers("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").permitAll();if(enabled)a.requestMatchers(HttpMethod.GET,"/api/spaces/{spaceId}/activity/timeline").permitAll();a.anyRequest().denyAll();}).build();}
}
