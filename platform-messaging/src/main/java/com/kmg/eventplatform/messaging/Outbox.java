package com.kmg.eventplatform.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes a message in the transaction of a business change: both are committed, or neither. The {@link OutboxRelay}
 * publishes it afterwards, so a crash between the commit and Kafka loses nothing.
 */
public class Outbox {

    private static final String INSERT = """
            INSERT INTO outbox_messages (message_id, topic, message_key, payload, traceparent, created_at)
            VALUES (:messageId, :topic, :key, :payload, :traceparent, :createdAt)
            """;

    private final JdbcClient jdbc;
    private final MessageCodec codec;
    private final TraceContextReader traceContext;
    private final Clock clock;

    Outbox(JdbcClient jdbc, MessageCodec codec, TraceContextReader traceContext, Clock clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.traceContext = traceContext;
        this.clock = clock;
    }

    /**
     * @param key     orders the messages of one aggregate: same key, same partition, delivered in the order written
     * @param type    what the message means, how consumers dispatch it
     * @param payload a record, written as JSON
     * @return the id of the message, the idempotency key of its consumers
     * @throws IllegalStateException outside a transaction
     */
    public UUID enqueue(String topic, String key, String type, Object payload) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Transactions.requireActive("Writing to the outbox");

        UUID messageId = UUID.randomUUID();
        Instant now = clock.instant();
        String envelope = codec.encode(new MessageEnvelope(messageId, type, now, codec.toTree(payload)));
        jdbc.sql(INSERT)
                .param("messageId", messageId)
                .param("topic", topic)
                .param("key", key)
                .param("payload", envelope)
                .param("traceparent", traceContext.currentTraceparent(), Types.VARCHAR)
                .param("createdAt", Timestamp.from(now))
                .update();
        return messageId;
    }
}
