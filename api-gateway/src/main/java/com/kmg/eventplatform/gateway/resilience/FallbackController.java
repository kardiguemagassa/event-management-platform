package com.kmg.eventplatform.gateway.resilience;

import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

/**
 * Target of the CircuitBreaker route filters ({@code forward:/fallback/unavailable}), for any HTTP method.
 * Turns a routing failure into an RFC 9457 problem with a stable code, like the services do.
 */
// Internal forward target of the CircuitBreaker filter, not a public endpoint.
@Hidden
@RestController
class FallbackController {

    static final String PATH = "/fallback/unavailable";

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping(PATH)
    ResponseEntity<ProblemDetail> unavailable(ServerWebExchange exchange) {
        Throwable failure = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        boolean timeout = isTimeout(failure);
        HttpStatus status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.SERVICE_UNAVAILABLE;

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, timeout
                ? "The service did not answer in time, please retry later"
                : "The service is temporarily unavailable, please retry later");
        problem.setProperty("code", timeout ? "UPSTREAM_TIMEOUT" : "SERVICE_UNAVAILABLE");
        String originalPath = exchange.getAttribute(OriginalPathFilter.ATTRIBUTE);
        if (originalPath != null) {
            problem.setInstance(URI.create(originalPath));
        }

        // Message only: a stack trace per failed request would flood the logs during an outage.
        log.warn("Routing of {} failed: {}", originalPath, String.valueOf(failure));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return true;
            }
            if (cause instanceof ResponseStatusException statusException
                    && statusException.getStatusCode().isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
                return true;
            }
        }
        return false;
    }
}
