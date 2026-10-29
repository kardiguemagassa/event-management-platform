package com.kmg.eventplatform.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * The part of the security filter chain shared by every servlet service: a stateless OAuth2 resource server
 * accepting Keycloak access tokens, with problem responses. Each service then adds its own access rules:
 * <pre>{@code
 * return platform.resourceServer(http)
 *         .authorizeHttpRequests(auth -> auth.requestMatchers("/api/**").authenticated().anyRequest().denyAll())
 *         .build();
 * }</pre>
 * Stateless: no session and no cookie, hence no CSRF protection (a bearer token is never sent automatically).
 */
public class PlatformHttpSecurity {

    private final SecurityProblemHandler problems;
    private final JwtAuthenticationConverter authenticationConverter;

    public PlatformHttpSecurity(SecurityProblemHandler problems, JwtAuthenticationConverter authenticationConverter) {
        this.problems = problems;
        this.authenticationConverter = authenticationConverter;
    }

    public HttpSecurity resourceServer(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)));
    }
}
