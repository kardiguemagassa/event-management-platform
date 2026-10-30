package com.kmg.eventplatform.observability;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applied to every service depending on this module. Export and sampling are standard Spring Boot properties
 * ({@code management.opentelemetry.tracing.export.otlp.*}, {@code management.opentelemetry.logging.export.otlp.*},
 * {@code management.tracing.sampling.probability}).
 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    ObservationPredicate platformNoiseFilter() {
        return new NoiseFilter();
    }

    @Bean
    OpenTelemetryLogsInstaller openTelemetryLogsInstaller(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new OpenTelemetryLogsInstaller(openTelemetry);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = Type.SERVLET)
    static class Servlet {

        @Bean
        ObservationPredicate servletActuatorFilter() {
            return new ServletActuatorFilter();
        }

        @Bean
        TraceIdProblemAdvice traceIdProblemAdvice(ObjectProvider<Tracer> tracer) {
            return new TraceIdProblemAdvice(tracer);
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
