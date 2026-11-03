package com.kmg.eventplatform.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCsrfMatcherTest {

    private final SessionCsrfMatcher matcher = new SessionCsrfMatcher();

    @Test
    void writeWithTheSessionCookieNeedsTheCsrfToken() {
        assertThat(matches(MockServerHttpRequest.post("/api/registrations").cookie(session()))).isTrue();
        assertThat(matches(MockServerHttpRequest.delete("/api/users/me").cookie(session()))).isTrue();
    }

    @Test
    void readsAreNeverChecked() {
        assertThat(matches(MockServerHttpRequest.get("/api/registrations/me").cookie(session()))).isFalse();
    }

    @Test
    void writeWithABearerTokenIsNotChecked() {
        assertThat(matches(MockServerHttpRequest.post("/api/registrations").cookie(session())
                .header("Authorization", "bearer eyJ"))).isFalse();
    }

    @Test
    void writeWithoutSessionCookieIsNotChecked() {
        assertThat(matches(MockServerHttpRequest.post("/api/registrations"))).isFalse();
        assertThat(matches(MockServerHttpRequest.post("/api/registrations").header("Authorization", "Basic x")))
                .isFalse();
    }

    private boolean matches(MockServerHttpRequest.BaseBuilder<?> request) {
        return matcher.matches(MockServerWebExchange.from(request)).block().isMatch();
    }

    private static HttpCookie session() {
        return new HttpCookie(SessionCsrfMatcher.SESSION_COOKIE, "id");
    }
}
