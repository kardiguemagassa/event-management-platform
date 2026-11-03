package com.kmg.eventplatform.messaging;

import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceContextReaderTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN_ID = "00f067aa0ba902b7";

    @Test
    void noTracerMeansNoTraceContext() {
        assertThat(reader(null).currentTraceparent()).isNull();
    }

    @Test
    void outsideASpanThereIsNoTraceContext() {
        assertThat(reader(tracerWith(null)).currentTraceparent()).isNull();
    }

    @Test
    void theCurrentSpanIsWrittenInTheW3cFormat() {
        assertThat(reader(tracerWith(context(true))).currentTraceparent())
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        assertThat(reader(tracerWith(context(false))).currentTraceparent())
                .isEqualTo("00-" + TRACE_ID + "-" + SPAN_ID + "-00");
        assertThat(reader(tracerWith(context(null))).currentTraceparent()).endsWith("-00");
    }

    @SuppressWarnings("unchecked")
    private static TraceContextReader reader(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracer);
        return new TraceContextReader(provider);
    }

    private static Tracer tracerWith(TraceContext context) {
        Tracer tracer = mock(Tracer.class);
        CurrentTraceContext current = mock(CurrentTraceContext.class);
        when(tracer.currentTraceContext()).thenReturn(current);
        when(current.context()).thenReturn(context);
        return tracer;
    }

    private static TraceContext context(Boolean sampled) {
        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE_ID);
        when(context.spanId()).thenReturn(SPAN_ID);
        when(context.sampled()).thenReturn(sampled);
        return context;
    }
}
