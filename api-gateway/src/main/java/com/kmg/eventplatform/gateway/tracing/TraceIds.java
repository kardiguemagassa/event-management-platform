package com.kmg.eventplatform.gateway.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

/**
 * The id of the trace of a request, for the problems written by the gateway itself (401, 403, fallback 503 and 504),
 * like the {@code traceId} the services add to theirs.
 * <p>
 * Read from the observation of the request, which WebFlux keeps in the exchange attributes: the security handlers
 * and the fallback run outside of any thread-local trace context, where {@code Tracer.currentSpan()} is empty.
 */
public final class TraceIds {

    public static final String PROPERTY = "traceId";

    private TraceIds() {
    }

    public static Optional<String> of(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.<TracingContext>get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(Span::context)
                .map(context -> context.traceId());
    }

    /** Adds {@code traceId} to the problem when the request is traced; leaves it as is otherwise. */
    public static ProblemDetail addTo(ProblemDetail problem, ServerWebExchange exchange) {
        of(exchange).ifPresent(traceId -> problem.setProperty(PROPERTY, traceId));
        return problem;
    }
}
