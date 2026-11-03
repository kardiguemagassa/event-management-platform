package com.kmg.eventplatform.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * After the gateway session is closed, the Keycloak session must end too: otherwise single sign-on would log the user
 * back in at once. Answers {@code 200 {"logoutUrl": ...}} rather than a redirect, so that a single-page application
 * (which calls {@code POST /logout} with its CSRF header) navigates the browser to Keycloak itself: an XHR cannot follow
 * a cross-origin redirect.
 */
final class KeycloakLogoutSuccessHandler implements ServerLogoutSuccessHandler {

    private final BffProperties bff;

    KeycloakLogoutSuccessHandler(BffProperties bff) {
        this.bff = bff;
    }

    @Override
    public Mono<Void> onLogoutSuccess(WebFilterExchange exchange, Authentication authentication) {
        ServerHttpResponse response = exchange.getExchange().getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"logoutUrl\":\"" + logoutUrl(authentication) + "\"}").getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    /** Keycloak end-session URL with the ID token as hint; the front-end URL when no OIDC session was open. */
    String logoutUrl(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return bff.frontUrl().toString();
        }
        return UriComponentsBuilder.fromUri(bff.endSessionUri())
                .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", bff.frontUrl())
                .encode()
                .build()
                .toUriString();
    }
}
