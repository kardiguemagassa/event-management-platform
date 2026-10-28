package com.kmg.eventplatform.gateway.filter;

import java.net.InetSocketAddress;
import java.net.URI;

import org.jspecify.annotations.NonNull;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * The gateway is the edge: no proxy sits in front of it, so client-supplied X-Forwarded-* /
 * Forwarded headers are untrusted. This filter recomputes them from the actual request, so that
 * backend services build public URLs (Location...) from values that cannot be spoofed.
 * <p>
 * Implemented as an {@link HttpHeadersFilter} ordered last: it runs when headers are prepared
 * for the downstream call, after the built-in filters that strip untrusted forwarded headers.
 *
 * @see <a href="https://spring.io/security/cve-2025-41235">CVE-2025-41235</a>
 */
@Component
public class ForwardedHeadersOverrideFilter implements HttpHeadersFilter, Ordered {

    static final String X_FORWARDED_HOST = "X-Forwarded-Host";
    static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    static final String X_FORWARDED_PORT = "X-Forwarded-Port";
    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    static final String X_FORWARDED_PREFIX = "X-Forwarded-Prefix";
    static final String FORWARDED = "Forwarded";

    @Override
    public @NonNull HttpHeaders filter(@NonNull HttpHeaders input, ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        URI uri = request.getURI();
        String host = request.getHeaders().getFirst(HttpHeaders.HOST);
        InetSocketAddress remoteAddress = request.getRemoteAddress();

        HttpHeaders headers = HttpHeaders.copyOf(input);
        headers.remove(FORWARDED);
        headers.remove(X_FORWARDED_PREFIX);
        headers.set(X_FORWARDED_HOST, host != null ? host : uri.getAuthority());
        headers.set(X_FORWARDED_PROTO, uri.getScheme());
        headers.set(X_FORWARDED_PORT, String.valueOf(port(uri)));
        if (remoteAddress != null) {
            headers.set(X_FORWARDED_FOR, remoteAddress.getAddress().getHostAddress());
        } else {
            headers.remove(X_FORWARDED_FOR);
        }
        return headers;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
