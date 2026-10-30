package com.kmg.eventplatform.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Sends the application logs over OTLP, next to the traces and metrics: Spring Boot exports log records
 * ({@code management.opentelemetry.logging.export.otlp.endpoint}) but does not install a Logback appender.
 * <p>
 * The appender is attached to the root logger by code, so no service needs a {@code logback-spring.xml}; the console
 * output is unchanged. Each record carries the trace and span ids of the current span: Grafana links a trace in Tempo
 * to its logs in Loki.
 * <p>
 * Idempotent: Spring Boot resets Logback when an application starts, and several contexts may start in one JVM
 * (tests), so the appender is attached only if missing; the {@link OpenTelemetry} instance is always the current one.
 */
final class OpenTelemetryLogsInstaller implements SmartInitializingSingleton {

    static final String APPENDER_NAME = "OTEL";

    private final ObjectProvider<OpenTelemetry> openTelemetry;

    OpenTelemetryLogsInstaller(ObjectProvider<OpenTelemetry> openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        OpenTelemetry sdk = openTelemetry.getIfAvailable();
        if (sdk == null) {
            return;
        }
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root.getAppender(APPENDER_NAME) == null) {
            OpenTelemetryAppender appender = new OpenTelemetryAppender();
            appender.setName(APPENDER_NAME);
            appender.setContext(context);
            appender.start();
            root.addAppender(appender);
        }
        OpenTelemetryAppender.install(sdk);
    }
}
