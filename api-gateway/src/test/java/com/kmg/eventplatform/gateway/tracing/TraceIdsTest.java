package com.kmg.eventplatform.gateway.tracing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdsTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Test
    void problemCarriesTheIdOfTheTraceOfTheRequest() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        TraceIds.addTo(problem, TracedExchanges.traced(exchange(), TRACE_ID));

        assertThat(problem.getProperties()).containsEntry("traceId", TRACE_ID);
    }

    @Test
    void requestObservedWithoutASpanHasNoTraceId() {
        MockServerWebExchange exchange = exchange();
        TracedExchanges.observed(exchange);

        assertThat(TraceIds.of(exchange)).isEmpty();
    }

    @Test
    void requestNotObservedLeavesTheProblemAsIs() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);

        TraceIds.addTo(problem, exchange());

        assertThat(problem.getProperties()).isNull();
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/users").build());
    }
}
