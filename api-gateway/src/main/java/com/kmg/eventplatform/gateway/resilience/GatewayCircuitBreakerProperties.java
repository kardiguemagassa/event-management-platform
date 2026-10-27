package com.kmg.eventplatform.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

import java.time.Duration;

/** Settings shared by the circuit breaker of every route. */
@ConfigurationProperties("gateway.circuit-breaker")
public record GatewayCircuitBreakerProperties(
        int slidingWindowSize,
        int minimumNumberOfCalls,
        float failureRateThreshold,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState) {

    public GatewayCircuitBreakerProperties {
        Assert.isTrue(slidingWindowSize > 0, "sliding-window-size must be positive");
        Assert.isTrue(minimumNumberOfCalls > 0, "minimum-number-of-calls must be positive");
        Assert.isTrue(failureRateThreshold > 0 && failureRateThreshold <= 100,
                "failure-rate-threshold must be in ]0, 100]");
        Assert.notNull(waitDurationInOpenState, "wait-duration-in-open-state is required");
        Assert.isTrue(permittedNumberOfCallsInHalfOpenState > 0,
                "permitted-number-of-calls-in-half-open-state must be positive");
    }
}
