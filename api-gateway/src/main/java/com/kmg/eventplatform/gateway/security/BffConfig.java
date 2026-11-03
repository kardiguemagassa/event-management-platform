package com.kmg.eventplatform.gateway.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Backend for Frontend: the gateway is also an OAuth2 client. A browser signs in through it and only ever holds an
 * HttpOnly session cookie; the tokens stay on the server and the TokenRelay filter adds the access token to every
 * routed call, refreshing it when it has expired.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BffProperties.class)
class BffConfig {

    /** Tokens kept in the web session: gone with it (logout, expiry), never shared between sessions. */
    @Bean
    ServerOAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new WebSessionServerOAuth2AuthorizedClientRepository();
    }

    /** Used by TokenRelay: returns the session's access token, refreshed with the refresh token when expired. */
    @Bean
    ReactiveOAuth2AuthorizedClientManager authorizedClientManager(ReactiveClientRegistrationRepository registrations,
                                                                  ServerOAuth2AuthorizedClientRepository clients) {
        DefaultReactiveOAuth2AuthorizedClientManager manager =
                new DefaultReactiveOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build());
        return manager;
    }

    /**
     * The CSRF token is generated lazily: subscribing to it on the BFF endpoints writes the {@code XSRF-TOKEN} cookie
     * that the front-end sends back in the {@code X-XSRF-TOKEN} header (Angular's HttpClient does it natively).
     */
    @Bean
    WebFilter csrfCookieWebFilter() {
        return (exchange, chain) -> {
            if (!exchange.getRequest().getPath().value().startsWith("/bff/")) {
                return chain.filter(exchange);
            }
            Mono<CsrfToken> token = exchange.getAttributeOrDefault(CsrfToken.class.getName(), Mono.empty());
            return token.then(chain.filter(exchange));
        };
    }
}
