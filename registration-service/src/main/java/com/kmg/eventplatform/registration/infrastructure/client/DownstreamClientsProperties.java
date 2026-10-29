package com.kmg.eventplatform.registration.infrastructure.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Base URLs use Eureka service ids (http://event-service), resolved by Spring Cloud LoadBalancer.
 */
@Validated
@ConfigurationProperties("registration.clients")
public record DownstreamClientsProperties(
        @NotBlank String eventServiceUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull @Valid Retry retry,
        @NotNull @Valid CircuitBreaker circuitBreaker) {

    /** Applies to transient failures only (see TransientDownstreamException). */
    public record Retry(
            @PositiveOrZero int maxRetries,
            @NotNull Duration delay,
            @NotNull Duration jitter) {
    }

    /** One breaker per downstream service, all sharing these settings. */
    public record CircuitBreaker(
            @Positive int slidingWindowSize,
            @Positive int minimumNumberOfCalls,
            @Positive @DecimalMax("100") float failureRateThreshold,
            @NotNull Duration waitDurationInOpenState,
            @Positive int permittedNumberOfCallsInHalfOpenState,
            @NotNull Duration maxWaitDurationInHalfOpenState) {
    }
}
