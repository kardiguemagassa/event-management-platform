package com.kmg.eventplatform.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Health of the outbox, read from its table at each metric push: how many messages wait for Kafka, and for how long
 * the oldest has waited. A growing age means the relay cannot publish (Kafka down, broken topic): an alert, since
 * the requests behind those messages are not answered meanwhile.
 * <p>
 * Without a unit on purpose: the Prometheus names are exactly {@code messaging_outbox_pending} and
 * {@code messaging_outbox_oldest_seconds}. A failed read reports NaN (no data) rather than a misleading zero.
 */
public class OutboxMetrics implements MeterBinder {

    static final String PENDING = "messaging.outbox.pending";
    static final String OLDEST = "messaging.outbox.oldest.seconds";

    private static final String COUNT_PENDING = "SELECT COUNT(*) FROM outbox_messages WHERE published_at IS NULL";
    private static final String OLDEST_PENDING =
            "SELECT MIN(created_at) FROM outbox_messages WHERE published_at IS NULL";

    private final JdbcClient jdbc;
    private final Clock clock;

    OutboxMetrics(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING, this, OutboxMetrics::pending)
                .description("Messages written to the outbox, not yet published to Kafka")
                .strongReference(true)
                .register(registry);
        Gauge.builder(OLDEST, this, OutboxMetrics::oldestSeconds)
                .description("Age of the oldest message not yet published to Kafka, 0 when none waits")
                .strongReference(true)
                .register(registry);
    }

    double pending() {
        try {
            return jdbc.sql(COUNT_PENDING).query(Long.class).single();
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }

    double oldestSeconds() {
        try {
            Optional<Timestamp> oldest = jdbc.sql(OLDEST_PENDING).query(Timestamp.class).list().stream()
                    .filter(Objects::nonNull)
                    .findFirst();
            return oldest.map(createdAt -> Duration.between(createdAt.toInstant(), clock.instant()).toMillis() / 1000.0)
                    .orElse(0.0);
        } catch (DataAccessException unavailable) {
            return Double.NaN;
        }
    }
}
