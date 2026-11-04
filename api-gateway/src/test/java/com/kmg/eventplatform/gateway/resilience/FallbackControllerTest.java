package com.kmg.eventplatform.gateway.resilience;

import com.kmg.eventplatform.gateway.tracing.TracedExchanges;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    void unreachableServiceIsUnavailable() {
        ResponseEntity<ProblemDetail> response = controller.unavailable(
                failedExchange(new ConnectException("Connection refused")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE");
        assertThat(response.getBody().getInstance()).isEqualTo(URI.create("/api/events/42"));
    }

    @Test
    void responseTimeoutIsAGatewayTimeout() {
        ResponseEntity<ProblemDetail> response = controller.unavailable(failedExchange(
                new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Response took longer than timeout",
                        new TimeoutException())));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "UPSTREAM_TIMEOUT");
    }

    @Test
    void problemCarriesTheIdOfTheTrace() {
        MockServerWebExchange exchange = TracedExchanges.traced(
                failedExchange(new ConnectException("Connection refused")), "4bf92f3577b34da6a3ce929d0e0e4736");

        ResponseEntity<ProblemDetail> response = controller.unavailable(exchange);

        assertThat(response.getBody().getProperties()).containsEntry("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
    }

    private static MockServerWebExchange failedExchange(Throwable failure) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(FallbackController.PATH).build());
        exchange.getAttributes().put(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR, failure);
        exchange.getAttributes().put(OriginalPathFilter.ATTRIBUTE, "/api/events/42");
        return exchange;
    }
}
