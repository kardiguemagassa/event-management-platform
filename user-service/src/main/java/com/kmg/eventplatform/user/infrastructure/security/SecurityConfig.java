package com.kmg.eventplatform.user.infrastructure.security;

import com.kmg.eventplatform.security.PlatformHttpSecurity;
import com.kmg.eventplatform.security.PlatformSecurityConfiguration;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Every user manages their own profile; the profiles of others are personal data, for administrators only. */
@Configuration(proxyBeanMethods = false)
@Import(PlatformSecurityConfiguration.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformHttpSecurity platform) throws Exception {
        return platform.resourceServer(http)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/api/users/me").authenticated()
                        .requestMatchers("/api/users", "/api/users/*").hasRole("ADMIN")
                        // Probes and info: exposed on the internal network only.
                        .requestMatchers(HttpMethod.GET, "/actuator/**").permitAll()
                        // API docs, aggregated by the gateway's Swagger UI; the local Swagger UI when run alone.
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
