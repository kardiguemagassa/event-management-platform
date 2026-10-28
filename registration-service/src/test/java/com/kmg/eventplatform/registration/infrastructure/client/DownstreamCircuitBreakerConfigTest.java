package com.kmg.eventplatform.registration.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import java.net.ConnectException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The production breaker configuration, exercised on a real Resilience4j circuit breaker (no Spring context). */
class DownstreamCircuitBreakerConfigTest {

    private static final String SERVICE = "event-service";

    @Test
    void missingInstancesNeverOpenTheCircuit() {
        CircuitBreaker breaker = breaker(Duration.ofSeconds(10));

        for (int i = 0; i < 20; i++) {
            callFailingWith(breaker, noInstance());
        }

        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void refusedConnectionsStillOpenTheCircuit() {
        CircuitBreaker breaker = breaker(Duration.ofSeconds(10));

        for (int i = 0; i < 10; i++) {
            callFailingWith(breaker, connectionRefused());
        }

        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }

    @Test
    void missingInstancesDoNotUseHalfOpenProbes() {
        CircuitBreaker breaker = halfOpen(breaker(Duration.ofSeconds(10)));

        for (int i = 0; i < 5; i++) {
            callFailingWith(breaker, noInstance());
        }
        assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);

        for (int i = 0; i < 3; i++) {
            breaker.executeSupplier(() -> "ok");
        }
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    @Test
    void halfOpenCircuitThatCannotConcludeGoesBackToOpen() {
        CircuitBreaker breaker = halfOpen(breaker(Duration.ofMillis(200)));

        await().atMost(Duration.ofSeconds(2)).until(() -> breaker.getState() == State.OPEN);
    }

    private static CircuitBreaker breaker(Duration maxWaitInHalfOpenState) {
        var settings = new DownstreamClientsProperties.CircuitBreaker(
                20, 10, 50, Duration.ofSeconds(10), 3, maxWaitInHalfOpenState);
        return CircuitBreaker.of(SERVICE, DownstreamClientsConfig.circuitBreakerConfig(settings));
    }

    private static CircuitBreaker halfOpen(CircuitBreaker breaker) {
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        return breaker;
    }

    private static void callFailingWith(CircuitBreaker breaker, DownstreamUnavailableException failure) {
        assertThatThrownBy(() -> breaker.executeSupplier(() -> {
            throw failure;
        })).isSameAs(failure);
    }

    private static DownstreamUnavailableException noInstance() {
        return new DownstreamUnavailableException(SERVICE,
                new NoInstanceAvailableException(new IllegalStateException("No instances available for " + SERVICE)));
    }

    private static DownstreamUnavailableException connectionRefused() {
        return new DownstreamUnavailableException(SERVICE,
                new TransientDownstreamException(new ConnectException("Connection refused")));
    }
}
