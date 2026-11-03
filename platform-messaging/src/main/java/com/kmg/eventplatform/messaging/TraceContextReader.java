package com.kmg.eventplatform.messaging;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The W3C {@code traceparent} of the current span, stored with a message so that its consumers continue the trace of
 * the request that wrote it (Spring Kafka listeners with observation read this header).
 */
class TraceContextReader {

    static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracer;

    TraceContextReader(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    /**
     * @return {@code null} without a tracer or outside a span
     */
    String currentTraceparent() {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return null;
        }
        TraceContext context = current.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
    }
}
