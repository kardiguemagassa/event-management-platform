package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaleRegistrationMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");

    private final RegistrationRepository repository = mock(RegistrationRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    StaleRegistrationMetricsTest() {
        new StaleRegistrationMetrics(repository, Clock.fixed(NOW, ZoneOffset.UTC)).bindTo(meters);
    }

    @Test
    void countsTheRegistrationsWaitingForMoreThanAMinute() {
        when(repository.countByStatusAndCreatedAtBefore(RegistrationStatus.PENDING, NOW.minusSeconds(60)))
                .thenReturn(3L);

        assertThat(meters.get(StaleRegistrationMetrics.STALE).gauge().value()).isEqualTo(3);
    }

    @Test
    void aFailedReadReportsNoData() {
        when(repository.countByStatusAndCreatedAtBefore(RegistrationStatus.PENDING, NOW.minusSeconds(60)))
                .thenThrow(new DataAccessResourceFailureException("Database down"));

        assertThat(meters.get(StaleRegistrationMetrics.STALE).gauge().value()).isNaN();
    }
}
