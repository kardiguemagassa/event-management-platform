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
}
