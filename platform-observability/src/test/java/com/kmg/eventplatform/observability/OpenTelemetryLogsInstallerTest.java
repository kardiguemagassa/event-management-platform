package com.kmg.eventplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class OpenTelemetryLogsInstallerTest {

    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private final OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setLoggerProvider(SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build())
            .setTracerProvider(SdkTracerProvider.builder().build())
            .build();

    @AfterEach
    void detachAppender() {
        root().detachAppender(OpenTelemetryLogsInstaller.APPENDER_NAME);
        sdk.close();
    }

    @Test
    void logsAreExportedWithTheTraceOfTheCurrentSpan() {
        installer(sdk).afterSingletonsInstantiated();

        Span span = sdk.getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggerFactory.getLogger("test").info("Registration {} confirmed", 42);
        } finally {
            span.end();
        }

        assertThat(exporter.getFinishedLogRecordItems()).anySatisfy(record -> {
            assertThat(record.getBodyValue().asString()).isEqualTo("Registration 42 confirmed");
            assertThat(record.getSpanContext().getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
        });
    }

    @Test
    void installingTwiceAttachesASingleAppender() {
        installer(sdk).afterSingletonsInstantiated();
        installer(sdk).afterSingletonsInstantiated();
        exporter.reset();

        LoggerFactory.getLogger("test").info("once");

        assertThat(exporter.getFinishedLogRecordItems())
                .filteredOn(record -> "once".equals(record.getBodyValue().asString()))
                .hasSize(1);
    }

    @Test
    void withoutOpenTelemetryNothingIsAttached() {
        new OpenTelemetryLogsInstaller(new StaticListableBeanFactory().getBeanProvider(OpenTelemetry.class))
                .afterSingletonsInstantiated();

        assertThat(root().getAppender(OpenTelemetryLogsInstaller.APPENDER_NAME)).isNull();
    }

    private static OpenTelemetryLogsInstaller installer(OpenTelemetry openTelemetry) {
        return new OpenTelemetryLogsInstaller(
                new StaticListableBeanFactory(Map.<String, Object>of("openTelemetry", openTelemetry)).getBeanProvider(OpenTelemetry.class));
    }

    private static Logger root() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
