package com.kmg.eventplatform.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Database down: no data rather than a reassuring zero. The values themselves are read in {@link MessagingIT}.
 */
class OutboxMetricsTest {

    @Test
    void aFailedReadReportsNoData() {
        JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(anyString())).thenThrow(new DataAccessResourceFailureException("Database down"));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new OutboxMetrics(jdbc, Clock.systemUTC()).bindTo(meters);

        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isNaN();
        assertThat(meters.get(OutboxMetrics.OLDEST).gauge().value()).isNaN();
    }
}
