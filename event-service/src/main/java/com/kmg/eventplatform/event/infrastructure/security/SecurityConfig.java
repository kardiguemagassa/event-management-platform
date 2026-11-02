package com.kmg.eventplatform.event.infrastructure.security;

import com.kmg.eventplatform.security.PlatformHttpSecurity;
import com.kmg.eventplatform.security.PlatformSecurityConfiguration;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Role rules of event-service (the gateway checks the token too: zero trust). Ownership of an event is checked
 * by the application layer: the role allows an action, being the organizer allows it on a given event.
 */
@Configuration(proxyBeanMethods = false)
@Import(PlatformSecurityConfiguration.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformHttpSecurity platform) throws Exception {
        return platform.resourceServer(http)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        // Seat reservation: service account of registration-service only.
                        .requestMatchers("/api/events/*/capacity/**").hasRole("SEAT_MANAGER")
                        // "My events" of an organizer, drafts included: the role, not just a token.
                        .requestMatchers(organizerOwnEvents()).hasRole("ORGANIZER")
                        // The catalog is public; drafts are filtered by the application layer.
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/*").permitAll()
                        .requestMatchers("/api/events", "/api/events/**").hasRole("ORGANIZER")
                        // Probes and info: exposed on the internal network only.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        // API docs, aggregated by the gateway's Swagger UI; the local Swagger UI when run alone.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /** {@code GET /api/events?organizer=...}: same path as the public catalog, told apart by its query parameter. */
    private static RequestMatcher organizerOwnEvents() {
        RequestMatcher catalog = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/events");
        return request -> catalog.matches(request) && request.getParameter("organizer") != null;
    }
}
