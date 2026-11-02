package com.kmg.eventplatform.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;

/**
 * Two ways in, one set of rules:
 * <ul>
 *   <li><b>Bearer token</b> (Swagger UI, API clients, tests): the gateway is an OAuth2 resource server. Stateless, no
 *   CSRF check (a bearer token is never sent automatically by a browser).</li>
 *   <li><b>Browser session</b> (BFF): {@code /oauth2/authorization/keycloak} signs in with Keycloak (authorization code
 *   with PKCE, confidential client {@code event-platform-bff}); the browser then holds an HttpOnly session cookie only,
 *   and the TokenRelay filter adds the session's access token to routed calls. Writes made with the cookie need the
 *   CSRF token ({@code XSRF-TOKEN} cookie sent back as {@code X-XSRF-TOKEN}); {@code POST /logout} also ends the
 *   Keycloak session.</li>
 * </ul>
 * Rules stay coarse here: read the public event catalog, otherwise be authenticated; each service enforces its own
 * roles (zero trust). Every refusal is an RFC 9457 problem: no redirect to a login page, the front-end decides.
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
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemDetailSecurityHandler problems,
                                                  ReactiveClientRegistrationRepository registrations,
                                                  BffProperties bff) {
        DefaultServerOAuth2AuthorizationRequestResolver authorizationRequests =
                new DefaultServerOAuth2AuthorizationRequestResolver(registrations);
        // PKCE for the confidential client too (OAuth 2.1, and required by the Keycloak client).
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        return http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieServerCsrfTokenRepository.withHttpOnlyFalse())
                        // Plain token, as a single-page application reads it from the cookie and sends it back.
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(new SessionCsrfMatcher())
                        .accessDeniedHandler(problems))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Internal routes of registration-service, reached through discovery and never from outside.
                        .pathMatchers("/api/events/*/capacity/**", "/api/users/names").denyAll()
                        .pathMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        .pathMatchers(HttpMethod.GET, API_DOCS).permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/api/**", "/bff/**").authenticated()
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
                .oauth2Login(login -> login
                        .authorizationRequestResolver(authorizationRequests)
                        .authenticationSuccessHandler(
                                new RedirectServerAuthenticationSuccessHandler(bff.frontUrl().toString()))
                        .authenticationFailureHandler(
                                new RedirectServerAuthenticationFailureHandler(bff.frontUrl() + "?login=failed")))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(new KeycloakLogoutSuccessHandler(bff)))
                .build();
    }
}
