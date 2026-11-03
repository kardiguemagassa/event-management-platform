package com.kmg.eventplatform.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The background tasks survive a database outage: an exception would cancel them for good.
 */
class OutboxRelayOutageTest {

    private static final MessagingProperties PROPERTIES = new MessagingProperties(true, Duration.ofMillis(50), 100,
            Duration.ofSeconds(1), Duration.ofDays(7), Duration.ofHours(1));

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    @SuppressWarnings("unchecked")
    private final OutboxRelay relay =
            new OutboxRelay(jdbc, transaction, mock(KafkaOperations.class), Clock.systemUTC(), PROPERTIES);

    @Test
    void theRelayKeepsPollingThroughADatabaseOutage() {
        when(transaction.execute(any())).thenThrow(new CannotCreateTransactionException("Database down"));

        assertThatCode(relay::relayUntilDrained).doesNotThrowAnyException();
        assertThatCode(relay::relayUntilDrained).doesNotThrowAnyException();
    }

    @Test
    void thePurgeIsRetriedLater() {
        when(jdbc.sql(anyString())).thenThrow(new DataAccessResourceFailureException("Database down"));

        assertThatCode(relay::purgeQuietly).doesNotThrowAnyException();
    }

    @Test
    void noResultCountsAsNothingPublished() {
        when(transaction.execute(any())).thenReturn(null);

        assertThat(relay.relay()).isZero();
    }
}
