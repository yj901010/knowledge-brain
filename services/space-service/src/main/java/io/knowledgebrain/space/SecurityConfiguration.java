package io.knowledgebrain.space;

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
    SecurityFilterChain securityFilterChain(HttpSecurity http,@Value("${brain.space.api.enabled:false}") boolean enabled,@Value("${brain.space.group-review-authorization.enabled:false}")boolean review,@Value("${brain.space.content-authorization.enabled:false}") boolean content,@Value("${brain.space.media-authorization.enabled:false}") boolean media,@Value("${brain.space.query-authorization.enabled:false}") boolean query,@Value("${brain.space.knowledge-authorization.enabled:false}") boolean knowledge,@Value("${brain.space.learning-authorization.enabled:false}") boolean learning,@Value("${brain.space.activity-authorization.enabled:false}") boolean activity,@Value("${brain.space.planning-authorization.enabled:false}") boolean planning,@Value("${brain.space.notification-authorization.enabled:false}")boolean notification,@Value("${brain.space.group-authorization.enabled:false}")boolean group,@Value("${brain.space.group-membership.enabled:false}")boolean membership) throws Exception {
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable());
        if(review)http.csrf(c->c.ignoringRequestMatchers("/internal/space/group-review-authorization"));
        if(membership)http.csrf(c->c.ignoringRequestMatchers("/api/groups/*/progress-sharing-policy","/api/group-invitations/accept","/api/groups/*/invitations","/api/groups/*/invitations/*/revoke","/api/groups/*/leave","/api/groups/*/members/*"));
        if(group)http.csrf(c->c.ignoringRequestMatchers("/internal/space/group-progress-sharing","/internal/space/group-access","/internal/space/group-provision","/internal/space/group-participation","/internal/space/group-participations"));
        if(notification)http.csrf(c->c.ignoringRequestMatchers("/internal/space/notification-authorization"));
        if(planning)http.csrf(c->c.ignoringRequestMatchers("/internal/space/planning-authorization"));
        if(activity)http.csrf(c->c.ignoringRequestMatchers("/internal/space/activity-authorization"));
        if(learning)http.csrf(c->c.ignoringRequestMatchers("/internal/space/learning-authorization"));
        if(knowledge)http.csrf(c->c.ignoringRequestMatchers("/internal/space/knowledge-authorization"));
        if(content)http.csrf(c->c.ignoringRequestMatchers("/internal/space/content-authorization"));
        if(query)http.csrf(c->c.ignoringRequestMatchers("/internal/space/query-authorization"));
        if(media)http.csrf(c->c.ignoringRequestMatchers("/internal/space/media-authorization"));
        return http.authorizeHttpRequests(auth -> {
            auth.requestMatchers("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
            if(enabled)auth.requestMatchers(HttpMethod.GET,"/api/spaces/public","/api/spaces/personal","/api/spaces/personal/all","/api/spaces/claims/{guestId}","/api/spaces/{spaceId}").permitAll();
            if(membership){
                auth.requestMatchers(HttpMethod.GET,"/api/groups/*/progress-sharing-policy","/api/groups/*/progress-sharing-policy/revisions").permitAll();auth.requestMatchers(HttpMethod.PUT,"/api/groups/*/progress-sharing-policy").permitAll();
                auth.requestMatchers(HttpMethod.GET,"/api/groups/*/membership","/api/groups/*/members","/api/groups/*/members/*/revisions","/api/groups/*/invitations","/api/groups/*/invitations/*/revisions").permitAll();
                auth.requestMatchers(HttpMethod.POST,"/api/group-invitations/accept","/api/groups/*/invitations","/api/groups/*/invitations/*/revoke","/api/groups/*/leave").permitAll();
                auth.requestMatchers(HttpMethod.PUT,"/api/groups/*/members/*").permitAll();auth.requestMatchers(HttpMethod.DELETE,"/api/groups/*/members/*").permitAll();
            }
            if(review)auth.requestMatchers(HttpMethod.POST,"/internal/space/group-review-authorization").permitAll();
            if(group)auth.requestMatchers(HttpMethod.POST,"/internal/space/group-progress-sharing","/internal/space/group-access","/internal/space/group-provision","/internal/space/group-participation","/internal/space/group-participations").permitAll();
            if(notification)auth.requestMatchers(HttpMethod.POST,"/internal/space/notification-authorization").permitAll();
            if(planning)auth.requestMatchers(HttpMethod.POST,"/internal/space/planning-authorization").permitAll();
            if(activity)auth.requestMatchers(HttpMethod.POST,"/internal/space/activity-authorization").permitAll();
            if(learning)auth.requestMatchers(HttpMethod.POST,"/internal/space/learning-authorization").permitAll();
            if(knowledge)auth.requestMatchers(HttpMethod.POST,"/internal/space/knowledge-authorization").permitAll();
            if(content)auth.requestMatchers(HttpMethod.POST,"/internal/space/content-authorization").permitAll();
            if(media)auth.requestMatchers(HttpMethod.POST,"/internal/space/media-authorization").permitAll();
            if(query)auth.requestMatchers(HttpMethod.POST,"/internal/space/query-authorization").permitAll();
            auth.anyRequest().denyAll();
        }).build();
    }
}
