package com.kmg.eventplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ObservabilityAutoConfigurationTest {

    private static final AutoConfigurations CONFIGURATION =
            AutoConfigurations.of(ObservabilityAutoConfiguration.class);

    @Test
    void servletServicesGetTheServletActuatorFilter() {
        new WebApplicationContextRunner().withConfiguration(CONFIGURATION).run(context -> {
            assertThat(context).hasSingleBean(NoiseFilter.class).hasSingleBean(ServletActuatorFilter.class)
                    .hasSingleBean(TraceIdProblemAdvice.class);
            assertThat(context).doesNotHaveBean(ReactiveActuatorFilter.class);
        });
    }

    @Test
    void reactiveGatewayGetsTheReactiveActuatorFilter() {
        new ReactiveWebApplicationContextRunner().withConfiguration(CONFIGURATION).run(context -> {
            assertThat(context).hasSingleBean(NoiseFilter.class).hasSingleBean(ReactiveActuatorFilter.class);
            assertThat(context).doesNotHaveBean(ServletActuatorFilter.class).doesNotHaveBean(TraceIdProblemAdvice.class);
        });
    }

    @Test
    void nonWebApplicationsOnlyFilterTheNoise() {
        new ApplicationContextRunner().withConfiguration(CONFIGURATION).run(context -> {
            assertThat(context).hasSingleBean(NoiseFilter.class).hasSingleBean(OpenTelemetryLogsInstaller.class);
            assertThat(context).doesNotHaveBean(ServletActuatorFilter.class).doesNotHaveBean(ReactiveActuatorFilter.class);
        });
    }
}
