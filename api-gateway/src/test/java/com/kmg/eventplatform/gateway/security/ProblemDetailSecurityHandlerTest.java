package com.kmg.eventplatform.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class ProblemDetailSecurityHandlerTest {

    private final ProblemDetailSecurityHandler handler = new ProblemDetailSecurityHandler(ServerCodecConfigurer.create());

    @Test
    void missingTokenGivesAPlainBearerChallenge() {
        MockServerWebExchange exchange = exchange();

        handler.commence(exchange, new AuthenticationCredentialsNotFoundException("Not Authenticated")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("UNAUTHENTICATED", "/api/users/7");
    }

    @Test
    void invalidTokenChallengeCarriesTheErrorCodeButNotItsDescription() {
        MockServerWebExchange exchange = exchange();

        handler.commence(exchange, new InvalidBearerTokenException("Jwt expired at 2026-10-29T08:00:00Z")).block();

        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(exchange.getResponse().getBodyAsString().block()).doesNotContain("expired");
    }

    @Test
    void accessDeniedIsAForbiddenProblem() {
        MockServerWebExchange exchange = exchange();

        handler.handle(exchange, new AccessDeniedException("Access Denied")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getFirst("WWW-Authenticate"))
                .isEqualTo("Bearer error=\"insufficient_scope\"");
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("ACCESS_DENIED");
    }

    @Test
    void failsFastWithoutAJsonCodec() {
        ServerCodecConfigurer noCodecs = ServerCodecConfigurer.create();
        noCodecs.registerDefaults(false);

        assertThatIllegalStateException().isThrownBy(() -> new ProblemDetailSecurityHandler(noCodecs));
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/users/7").build());
    }
}
