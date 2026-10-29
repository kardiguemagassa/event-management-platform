package com.kmg.eventplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

class TraceIdProblemAdviceTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final Tracer tracer = mock(Tracer.class);

    @SuppressWarnings("unchecked")
    private TraceIdProblemAdvice advice(Tracer available) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new TraceIdProblemAdvice(provider);
    }

    private static Object write(TraceIdProblemAdvice advice, Object body) {
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_PROBLEM_JSON, null, null, null);
    }

    @Test
    void problemCarriesTheIdOfTheCurrentTrace() {
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        write(advice(tracer), problem);

        assertThat(problem.getProperties()).containsEntry("traceId", TRACE_ID);
    }

    @Test
    void problemOutsideOfATraceIsLeftAsIs() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

        write(advice(tracer), problem);

        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void problemWithoutTracingIsLeftAsIs() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);

        write(advice(null), problem);

        assertThat(problem.getProperties()).isNull();
    }

    @Test
    void otherBodiesAreLeftAsIs() {
        Object body = "{\"id\":42}";

        assertThat(write(advice(tracer), body)).isSameAs(body);
        assertThat(advice(tracer).supports(null, null)).isTrue();
    }
}
