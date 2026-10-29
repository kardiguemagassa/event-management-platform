package com.kmg.eventplatform.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Hands the failures detected by the security filter chain to Spring MVC, so that {@link SecurityProblemAdvice}
 * writes them with the application's own message converters: same JSON as every other problem of the service.
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public SecurityProblemHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        resolve(request, response, failure, HttpStatus.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        resolve(request, response, denied, HttpStatus.FORBIDDEN);
    }

    /** Never an empty 200: if no handler takes the failure, the bare status is still sent. */
    private void resolve(HttpServletRequest request, HttpServletResponse response, Exception failure,
                         HttpStatus fallback) throws IOException {
        if (resolver.resolveException(request, response, null, failure) == null) {
            response.sendError(fallback.value());
        }
    }
}
