package com.kmg.eventplatform.gateway.resilience;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Remembers the client's path before any routing: the circuit breaker forwards failures to the fallback,
 * which then only sees its own path. The gateway's original-URL attribute is not set on every route.
 */
@Component
class OriginalPathFilter implements WebFilter, Ordered {

    static final String ATTRIBUTE = OriginalPathFilter.class.getName() + ".path";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getAttributes().putIfAbsent(ATTRIBUTE, exchange.getRequest().getPath().value());
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
