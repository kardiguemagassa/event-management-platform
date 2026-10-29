package com.kmg.eventplatform.gateway.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * Writes authentication (401) and authorization (403) failures as RFC 9457 problems with a stable code, like
 * every other error of the platform, and with the RFC 6750 {@code WWW-Authenticate} challenge.
 * <p>
 * The problem is written with the application's own codecs, so its JSON is identical to the one of controllers.
 * The challenge carries the error code only: the error description (expiry date, failed claim...) stays out.
 */
@Component
class ProblemDetailSecurityHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private static final ResolvableType PROBLEM_TYPE = ResolvableType.forClass(ProblemDetail.class);

    private final HttpMessageWriter<ProblemDetail> writer;

    ProblemDetailSecurityHandler(ServerCodecConfigurer codecs) {
        this.writer = problemWriter(codecs);
    }

    @Override
    public @NonNull Mono<Void> commence(@NonNull ServerWebExchange exchange, @NonNull AuthenticationException failure) {
        // No token: plain challenge. Invalid token: the OAuth2 error code (invalid_token, invalid_request).
        String challenge = failure instanceof OAuth2AuthenticationException oauth2
                ? "Bearer error=\"" + oauth2.getError().getErrorCode() + "\""
                : "Bearer";
        return write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid access token is required", challenge);
    }

    @Override
    public @NonNull Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull AccessDeniedException denied) {
        return write(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You are not allowed to access this resource", "Bearer error=\"insufficient_scope\"");
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail,
                             String challenge) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        return writer.write(Mono.just(problem), PROBLEM_TYPE, MediaType.APPLICATION_PROBLEM_JSON, response, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static HttpMessageWriter<ProblemDetail> problemWriter(ServerCodecConfigurer codecs) {
        return codecs.getWriters().stream()
                .filter(writer -> writer.canWrite(PROBLEM_TYPE, MediaType.APPLICATION_PROBLEM_JSON))
                .map(writer -> (HttpMessageWriter<ProblemDetail>) writer)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No codec can write application/problem+json"));
    }
}
