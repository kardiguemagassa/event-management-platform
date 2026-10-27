package com.kmg.eventplatform.registration.infrastructure.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Base URLs use Eureka service ids (http://user-service), resolved by Spring Cloud LoadBalancer.
 */
@Validated
@ConfigurationProperties("registration.clients")
public record DownstreamClientsProperties(
        @NotBlank String userServiceUrl,
        @NotBlank String eventServiceUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout) {
}
