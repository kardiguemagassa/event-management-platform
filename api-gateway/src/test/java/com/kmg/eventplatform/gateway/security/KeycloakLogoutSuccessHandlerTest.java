package com.kmg.eventplatform.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.server.WebFilterExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakLogoutSuccessHandlerTest {

    private static final String REALM = "http://localhost:8180/realms/event-platform";

    private final KeycloakLogoutSuccessHandler handler = new KeycloakLogoutSuccessHandler(
            new BffProperties(URI.create("http://localhost:4200/"), URI.create(REALM + "/protocol/openid-connect/logout")));

    @Test
    void oidcSessionEndsAtKeycloakWithTheIdTokenAsHint() {
        String url = handler.logoutUrl(oidcLogin());

        assertThat(url).startsWith(REALM + "/protocol/openid-connect/logout?")
                .contains("id_token_hint=the-id-token", "post_logout_redirect_uri=http://localhost:4200/");
    }

    @Test
    void withoutOidcSessionTheBrowserGoesBackToTheFrontEnd() {
        assertThat(handler.logoutUrl(null)).isEqualTo("http://localhost:4200/");
        assertThat(handler.logoutUrl(new TestingAuthenticationToken("user", "n/a"))).isEqualTo("http://localhost:4200/");
    }

    @Test
    void answersTheLogoutUrlAsJsonForTheSinglePageApplication() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/logout"));

        handler.onLogoutSuccess(new WebFilterExchange(exchange, ignored -> Mono.empty()), oidcLogin()).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(200);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .startsWith("{\"logoutUrl\":\"" + REALM + "/protocol/openid-connect/logout?");
    }

    private static OAuth2AuthenticationToken oidcLogin() {
        OidcIdToken idToken = OidcIdToken.withTokenValue("the-id-token")
                .subject("user-id").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        DefaultOidcUser user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, idToken);
        return new OAuth2AuthenticationToken(user, AuthorityUtils.NO_AUTHORITIES, "keycloak");
    }
}
