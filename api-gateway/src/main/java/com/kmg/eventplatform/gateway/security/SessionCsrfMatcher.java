package com.kmg.eventplatform.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * CSRF protection where a forgery is possible: a state-changing request authenticated by the session cookie, which a
 * browser sends on its own. A bearer token is never sent automatically, and without a session cookie there is nothing
 * to forge: those requests are not checked (and keep their 401 when anonymous).
 */
final class SessionCsrfMatcher implements ServerWebExchangeMatcher {

    static final String SESSION_COOKIE = "SESSION";
    private static final Set<HttpMethod> SAFE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    public Mono<MatchResult> matches(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        boolean sessionWrite = !SAFE_METHODS.contains(request.getMethod())
                && !hasBearerToken(request)
                && request.getCookies().containsKey(SESSION_COOKIE);
        return sessionWrite ? MatchResult.match() : MatchResult.notMatch();
    }

    private static boolean hasBearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return authorization != null
                && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }
}
