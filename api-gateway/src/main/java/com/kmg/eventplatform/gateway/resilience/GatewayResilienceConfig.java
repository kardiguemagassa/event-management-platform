package com.kmg.eventplatform.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayCircuitBreakerProperties.class)
class GatewayResilienceConfig {

    /**
     * Default settings for the breakers created by the CircuitBreaker route filters (one per route).
     * Every routing error (connection refused, no instance, timeout) counts as a failure;
     * HTTP responses, including 5xx from the service itself, do not.
     */
    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> routeCircuitBreakers(
            GatewayCircuitBreakerProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(config)
                .build());
    }
}
