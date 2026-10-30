package com.kmg.eventplatform.registration.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics of the registrations, next to the technical ones (HTTP, JVM, database): what the platform does
 * for its users. Exported like every Micrometer meter (OTLP, then Prometheus as {@code registrations_*_total}).
 * <p>
 * The rejection reason is the stable error {@code code} of a sealed hierarchy: a small, bounded set of label values.
 */
@Component
public class RegistrationMetrics {

    static final String CONFIRMED = "registrations.confirmed";
    static final String CANCELLED = "registrations.cancelled";
    static final String REJECTED = "registrations.rejected";
    static final String REASON = "reason";

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter cancelled;

    public RegistrationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder(CONFIRMED).description("Registrations confirmed (seat reserved)")
                .register(registry);
        this.cancelled = Counter.builder(CANCELLED).description("Registrations cancelled by their attendee (seat released)")
                .register(registry);
    }

    void confirmed() {
        confirmed.increment();
    }

    void cancelled() {
        cancelled.increment();
    }

    /** @param reason the error code of the rejection, e.g. {@code EVENT_FULL} */
    void rejected(String reason) {
        Counter.builder(REJECTED).description("Registration attempts rejected, by reason").tag(REASON, reason)
                .register(registry).increment();
    }
}
