package com.kmg.eventplatform.gateway.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Puts the observation of a traced request in an exchange, as WebFlux does for a real request. */
public final class TracedExchanges {

    private TracedExchanges() {
    }

    public static MockServerWebExchange traced(MockServerWebExchange exchange, String traceId) {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        TracingContext tracing = new TracingContext();
        tracing.setSpan(span);
        observed(exchange).put(TracingContext.class, tracing);
        return exchange;
    }

    /** An observed request without a span (tracing disabled or not sampled). */
    public static ServerRequestObservationContext observed(MockServerWebExchange exchange) {
        ServerRequestObservationContext observation = new ServerRequestObservationContext(
                exchange.getRequest(), exchange.getResponse(), exchange.getAttributes());
        exchange.getAttributes().put(ServerRequestObservationContext.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, observation);
        return observation;
    }
}
