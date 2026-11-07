package io.knowledgebrain.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;

@Configuration
class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.identity.api.enabled:false}") boolean enabled) throws Exception {
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());
        if(enabled)http.csrf(c->c.ignoringRequestMatchers("/api/identity/guest-session","/api/identity/guest-session/revoke","/internal/identity/guest-introspection","/api/identity/login"));
        return http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
            if(enabled) {
                auth.requestMatchers(HttpMethod.GET,"/api/identity/guest-session","/api/identity/login/callback","/api/identity/account-session").permitAll();
                auth.requestMatchers(HttpMethod.POST,"/api/identity/guest-session","/api/identity/guest-session/revoke","/internal/identity/guest-introspection","/api/identity/login").permitAll();
            }
            auth.anyRequest().denyAll();
        }).build();
    }
}
