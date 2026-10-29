package com.kmg.eventplatform.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applied to every service depending on this module. Export and sampling are standard Spring Boot properties
 * ({@code management.opentelemetry.tracing.export.otlp.*}, {@code management.tracing.sampling.probability}).
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    ObservationPredicate platformNoiseFilter() {
        return new NoiseFilter();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class Servlet {

        @Bean
        ObservationPredicate servletActuatorFilter() {
            return new ServletActuatorFilter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.REACTIVE)
    static class Reactive {

        @Bean
        ObservationPredicate reactiveActuatorFilter() {
            return new ReactiveActuatorFilter();
        }
    }
}
