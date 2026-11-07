package io.knowledgebrain.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.gateway.api.enabled:false}") boolean enabled) throws Exception {
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());
        if(enabled)http.csrf(c->c.ignoringRequestMatchers(request->java.util.Set.of("POST","PUT","DELETE").contains(request.getMethod()) && GatewayRoute.select(request.getMethod(),request.getRequestURI())!=null));
        return http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
            if(enabled)auth.requestMatchers(org.springframework.http.HttpMethod.GET,"/graph","/graph/","/graph/knowledge","/graph/knowledge/","/graph/roadmaps","/graph/roadmaps/","/graph/groups","/graph/groups/","/graph/media-config","/graph/_next/static/**").permitAll();
            if(enabled)auth.requestMatchers(request->GatewayRoute.select(request.getMethod(),request.getRequestURI())!=null).permitAll();
            auth.anyRequest().denyAll();
        }).build();
    }
}
