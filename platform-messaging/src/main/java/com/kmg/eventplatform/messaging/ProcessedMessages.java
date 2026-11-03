package com.kmg.eventplatform.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/**
 * Idempotent consumption: Kafka delivers at least once, so a consumer records each message it handles, in the
 * transaction of its effect. A redelivered message is then recognized and skipped.
 */
public class ProcessedMessages {

    private static final String INSERT = """
            INSERT INTO processed_messages (message_id, processed_at) VALUES (:messageId, :processedAt)
            ON CONFLICT (message_id) DO NOTHING
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    ProcessedMessages(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @return {@code true} the first time, {@code false} for a message already handled: skip it
     * @throws IllegalStateException outside a transaction
     */
    public boolean markProcessed(UUID messageId) {
        Transactions.requireActive("Recording a processed message");
        return jdbc.sql(INSERT)
                .param("messageId", messageId)
                .param("processedAt", Timestamp.from(clock.instant()))
                .update() == 1;
    }
}
