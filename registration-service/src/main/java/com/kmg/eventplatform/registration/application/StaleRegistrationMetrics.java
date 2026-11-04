package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Registrations still waiting for their seat after a minute ({@code registrations_pending_stale}): an answer
 * normally comes in under a second, so a waiting attendee means the saga is stuck (event-service or Kafka down, a
 * message in a dead letter topic). They are not expired automatically: their messages are durable and will be
 * answered; the gauge, and its alert, tell that someone is waiting meanwhile.
 */
@Component
public class StaleRegistrationMetrics implements MeterBinder {

    static final String STALE = "registrations.pending.stale";
    static final Duration STALE_AFTER = Duration.ofMinutes(1);

    private final RegistrationRepository repository;
    private final Clock clock;

    public StaleRegistrationMetrics(RegistrationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(STALE, this, StaleRegistrationMetrics::stale)
                .description("Registrations waiting for their seat for more than a minute")
                .strongReference(true)
                .register(registry);
    }

    /** NaN (no data) when the database cannot be read, rather than a reassuring zero. */
    double stale() {
        try {
            return repository.countByStatusAndCreatedAtBefore(RegistrationStatus.PENDING,
                    clock.instant().minus(STALE_AFTER));
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }
}
