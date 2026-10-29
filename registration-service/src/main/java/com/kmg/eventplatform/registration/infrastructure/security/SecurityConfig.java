package com.kmg.eventplatform.registration.infrastructure.security;

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
 * Role rules of registration-service (the gateway checks the token too: zero trust). Ownership is checked by
 * the application layer: a registration belongs to its attendee, the registrations of an event to its organizer.
 */
@Configuration(proxyBeanMethods = false)
@Import(PlatformSecurityConfiguration.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformHttpSecurity platform) throws Exception {
        return platform.resourceServer(http)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        // Probes, info and circuit breaker state: exposed on the internal network only.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        // API docs, aggregated by the gateway's Swagger UI; the local Swagger UI when run alone.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/registrations/events/*").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.POST, "/api/registrations", "/api/registrations/*/cancel")
                        .hasRole("ATTENDEE")
                        .requestMatchers(HttpMethod.GET, "/api/registrations/me", "/api/registrations/*")
                        .authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}
