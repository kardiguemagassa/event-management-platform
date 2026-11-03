package com.kmg.eventplatform.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes the outbox to Kafka, oldest first (polling publisher). A message is marked published only once Kafka has
 * acknowledged it: delivery is at least once, consumers are idempotent ({@link ProcessedMessages}).
 * <p>
 * Order: the relay stops at the first failure and retries from there at the next poll, so no message overtakes an
 * older one. Rows are locked ({@code FOR UPDATE SKIP LOCKED}): two relays never publish the same row, but the order
 * of a key holds with one active relay per service (one instance today; a lock such as ShedLock with several).
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String SELECT_PENDING = """
            SELECT sequence, topic, message_key, payload, traceparent FROM outbox_messages
            WHERE published_at IS NULL ORDER BY sequence LIMIT :limit FOR UPDATE SKIP LOCKED
            """;
    private static final String MARK_PUBLISHED =
            "UPDATE outbox_messages SET published_at = :publishedAt WHERE sequence IN (:sequences)";
    private static final String PURGE_OUTBOX = "DELETE FROM outbox_messages WHERE published_at < :before";
    private static final String PURGE_PROCESSED = "DELETE FROM processed_messages WHERE processed_at < :before";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final KafkaOperations<String, String> kafka;
    private final Clock clock;
    private final MessagingProperties properties;

    private volatile ScheduledExecutorService executor;
    /** Logs an outage once, not at every poll. */
    private volatile boolean failing;

    OutboxRelay(JdbcClient jdbc, TransactionTemplate transaction, KafkaOperations<String, String> kafka, Clock clock,
                MessagingProperties properties) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.kafka = kafka;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * One batch, in one transaction: read, publish, mark.
     *
     * @return how many messages were published
     */
    public int relay() {
        Integer published = transaction.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    /**
     * @return how many rows were deleted (published messages and processed message ids past the retention)
     */
    public int purge() {
        Timestamp before = Timestamp.from(clock.instant().minus(properties.retention()));
        int outbox = jdbc.sql(PURGE_OUTBOX).param("before", before).update();
        int processed = jdbc.sql(PURGE_PROCESSED).param("before", before).update();
        return outbox + processed;
    }

    private int publishBatch() {
        List<PendingMessage> pending = jdbc.sql(SELECT_PENDING)
                .param("limit", properties.batchSize())
                .query((row, number) -> new PendingMessage(row.getLong("sequence"), row.getString("topic"),
                        row.getString("message_key"), row.getString("payload"), row.getString("traceparent")))
                .list();
        List<Long> published = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (!send(message)) {
                break;
            }
            published.add(message.sequence());
        }
        if (!published.isEmpty()) {
            jdbc.sql(MARK_PUBLISHED)
                    .param("publishedAt", Timestamp.from(clock.instant()))
                    .param("sequences", published)
                    .update();
        }
        if (failing && published.size() == pending.size()) {
            failing = false;
            log.info("Outbox relay publishing again");
        }
        return published.size();
    }

    private boolean send(PendingMessage message) {
        RecordHeaders headers = new RecordHeaders();
        if (message.traceparent() != null) {
            headers.add(TraceContextReader.TRACEPARENT, message.traceparent().getBytes(StandardCharsets.UTF_8));
        }
        ProducerRecord<String, String> record =
                new ProducerRecord<>(message.topic(), null, message.key(), message.payload(), headers);
        try {
            kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            reportFailure(message, failure);
            return false;
        }
    }

    private void reportFailure(PendingMessage message, Exception failure) {
        if (!failing) {
            failing = true;
            log.warn("Outbox message {} not published, retried at the next poll: {}", message.sequence(),
                    failure.toString());
        } else {
            log.debug("Outbox message {} still not published: {}", message.sequence(), failure.toString());
        }
    }

    /**
     * Drains the outbox (full batches follow each other), then waits for the next poll. Never throws: an exception
     * would cancel the scheduled task.
     */
    void relayUntilDrained() {
        try {
            int published;
            do {
                published = relay();
            } while (published == properties.batchSize() && isRunning());
        } catch (RuntimeException unavailable) {
            if (!failing) {
                failing = true;
                log.warn("Outbox relay failed, retried at the next poll: {}", unavailable.toString());
            }
        }
    }

    void purgeQuietly() {
        try {
            int deleted = purge();
            log.debug("Messaging purge: {} rows deleted", deleted);
        } catch (RuntimeException failure) {
            log.warn("Messaging purge failed, retried later: {}", failure.toString());
        }
    }

    @Override
    public void start() {
        ScheduledExecutorService started = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("outbox-relay").daemon().factory());
        // Running before the first poll: a full batch is followed by the next one at once.
        executor = started;
        started.scheduleWithFixedDelay(this::relayUntilDrained, 0, properties.pollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        long purgeInterval = properties.purgeInterval().toMillis();
        started.scheduleWithFixedDelay(this::purgeQuietly, purgeInterval, purgeInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Lets the batch in progress finish for one send timeout, then interrupts it: its unmarked messages are published
     * again at the next start (at least once).
     */
    @Override
    public void stop() {
        ScheduledExecutorService stopping = executor;
        executor = null;
        if (stopping == null) {
            return;
        }
        stopping.shutdown();
        try {
            if (!stopping.awaitTermination(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                stopping.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            stopping.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    @Override
    public boolean isAutoStartup() {
        return properties.relayEnabled();
    }

    private record PendingMessage(long sequence, String topic, String key, String payload, String traceparent) {
    }
}
