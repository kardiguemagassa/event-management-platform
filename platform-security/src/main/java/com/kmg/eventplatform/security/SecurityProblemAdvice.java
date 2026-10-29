package com.kmg.eventplatform.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Authentication (401) and authorization (403) failures as RFC 9457 problems with a stable code, plus the
 * RFC 6750 {@code WWW-Authenticate} challenge (error code only: the description may reveal token details).
 * <p>
 * Highest precedence: it wins over the catch-all handler of each service, which would turn a refusal into a 500.
 * Failures detected by the filter chain reach it through {@link SecurityProblemHandler}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityProblemAdvice {

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException failure, HttpServletRequest request) {
        String challenge = failure instanceof OAuth2AuthenticationException oauth2
                ? "Bearer error=\"" + oauth2.getError().getErrorCode() + "\""
                : "Bearer";
        return problem(HttpStatus.UNAUTHORIZED, "Authentication required", "A valid access token is required",
                "UNAUTHENTICATED", challenge, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException denied, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", "You are not allowed to access this resource",
                "ACCESS_DENIED", "Bearer error=\"insufficient_scope\"", request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String code,
                                                         String challenge, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status)
                .header(HttpHeaders.WWW_AUTHENTICATE, challenge)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
