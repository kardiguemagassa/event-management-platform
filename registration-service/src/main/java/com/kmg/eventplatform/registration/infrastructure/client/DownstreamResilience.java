package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.retry.RetryTemplate;

import java.util.function.Supplier;

/**
 * Retry inside a circuit breaker, one breaker per downstream service.
 * <ul>
 *     <li>Only failures marked {@link TransientDownstreamException} are retried.</li>
 *     <li>Only {@link DownstreamUnavailableException} is recorded by the breaker: business rejections
 *     (404, 409) are answers, not outages.</li>
 *     <li>An open circuit fails fast with {@link DownstreamUnavailableException}, without any network call.</li>
 * </ul>
 */
final class DownstreamResilience {

    private final CircuitBreakerFactory<?, ?> circuitBreakers;
    private final RetryTemplate retry;

    DownstreamResilience(CircuitBreakerFactory<?, ?> circuitBreakers, RetryTemplate retry) {
        this.circuitBreakers = circuitBreakers;
        this.retry = retry;
    }

    <T> T call(String service, Supplier<T> call) {
        return circuitBreakers.create(service).run(() -> retry.invoke(() -> call.get()), failure -> {
            throw propagate(service, failure);
        });
    }

    private static RuntimeException propagate(String service, Throwable failure) {
        if (failure instanceof CallNotPermittedException) {
            return new DownstreamUnavailableException(service, "circuit breaker is open");
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }
}
