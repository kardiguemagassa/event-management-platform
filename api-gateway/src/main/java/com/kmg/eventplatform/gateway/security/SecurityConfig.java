package com.kmg.eventplatform.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * The gateway is an OAuth2 resource server: every call to the API needs a valid Keycloak access token, except
 * reads of the public event catalog. Rules stay coarse here; each service enforces its own roles (zero trust).
 * <p>
 * Stateless: no session, no cookie, hence no CSRF protection (a bearer token is never sent automatically by a
 * browser). A cookie-based BFF login for the web front-end would re-enable CSRF for its own routes.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfig {

    /** Swagger UI and the API docs it aggregates: documentation only, no data. */
    private static final String[] API_DOCS = {
            "/swagger-ui.html", "/swagger-ui/**", "/webjars/swagger-ui/**",
            "/v3/api-docs", "/v3/api-docs/**", "/api-docs/**"
    };

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemDetailSecurityHandler problems) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        // Internal route of registration-service, reached through discovery and never from outside.
                        .pathMatchers("/api/events/*/capacity/**").denyAll()
                        .pathMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        .pathMatchers(HttpMethod.GET, API_DOCS).permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        // Deny by default, including the internal fallback target of the circuit breakers
                        // (the gateway forwards to it internally, after this filter chain).
                        .anyExchange().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(Customizer.withDefaults()))
                .build();
    }
}
