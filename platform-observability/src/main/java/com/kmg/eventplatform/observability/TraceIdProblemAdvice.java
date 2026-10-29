package com.kmg.eventplatform.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Adds the id of the current trace to every RFC 9457 problem written by Spring MVC: business errors, validation,
 * 401/403 and unavailable dependencies alike. A client reporting an error gives that id, which leads straight to
 * the trace in Tempo and to the matching log lines.
 */
@ControllerAdvice
public class TraceIdProblemAdvice implements ResponseBodyAdvice<Object> {

    static final String TRACE_ID = "traceId";

    private final ObjectProvider<Tracer> tracer;

    public TraceIdProblemAdvice(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) {
            Tracer current = tracer.getIfAvailable();
            Span span = current == null ? null : current.currentSpan();
            if (span != null) {
                problem.setProperty(TRACE_ID, span.context().traceId());
            }
        }
        return body;
    }
}
