package com.kmg.eventplatform.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Logs every request reaching the gateway (method, path, status, duration),
 * including unmatched routes (404), which a route-level GlobalFilter would miss.
 * <ul>
 *   <li>The query string is omitted because it may contain personal data.</li>
 *   <li>Actuator endpoints are skipped to avoid flooding logs with health probes.</li>
 *   <li>Logging happens just before the response is committed, so the final status
 *       set by error handlers is captured.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String ACTUATOR_PATH = "/actuator";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getRawPath();

        if (path.startsWith(ACTUATOR_PATH)) {
            return chain.filter(exchange);
        }

        long start = System.nanoTime();
        exchange.getResponse().beforeCommit(() -> {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            log.info("{} {} -> {} ({} ms)",
                    request.getMethod(), path, exchange.getResponse().getStatusCode(), durationMs);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}
