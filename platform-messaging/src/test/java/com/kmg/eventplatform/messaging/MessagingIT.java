package com.kmg.eventplatform.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import com.kmg.eventplatform.messaging.test.KafkaTestContainer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The outbox against a real PostgreSQL and a real Kafka: what is committed is published, in order and with its trace
 * context; what is rolled back never is; a failing consumer ends in the dead letter topic.
 */
@SpringBootTest(classes = MessagingIT.TestApplication.class, properties = {
        "platform.messaging.relay-enabled=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "management.tracing.sampling.probability=1.0"
})
class MessagingIT {

    static final String POISON_TOPIC = "it.poison";

    @Autowired
    Outbox outbox;
    @Autowired
    OutboxRelay relay;
    @Autowired
    ProcessedMessages processedMessages;
    @Autowired
    MessageCodec codec;
    @Autowired
    DataSource dataSource;
    @Autowired
    ConsumerFactory<String, String> consumerFactory;
    @Autowired
    ProducerFactory<String, String> producerFactory;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    Tracer tracer;
    @Autowired
    PoisonListener poisonListener;
    @Autowired
    OutboxMetrics outboxMetrics;
    @Autowired
    SimpleMeterRegistry meterRegistry;
    @Autowired
    PlatformTransactionManager transactionManager;

    JdbcClient jdbc;
    TransactionTemplate transaction;

    record Sample(String name) {
    }

    @BeforeEach
    void emptyTables() {
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        jdbc.sql("DELETE FROM outbox_messages").update();
        jdbc.sql("DELETE FROM processed_messages").update();
    }

    @Test
    void committedMessagesArePublishedInOrderWithTheTraceOfTheirRequest() {
        String topic = uniqueTopic();
        Span request = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            transaction.executeWithoutResult(status -> {
                outbox.enqueue(topic, "event-1", "ReserveSeat", new Sample("first"));
                outbox.enqueue(topic, "event-1", "ReserveSeat", new Sample("second"));
            });
        } finally {
            request.end();
        }

        assertThat(relay.relay()).isEqualTo(2);

        List<ConsumerRecord<String, String>> records = consume(topic, 2);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly("event-1");
        List<MessageEnvelope> envelopes = records.stream().map(record -> codec.decode(record.value())).toList();
        assertThat(envelopes).extracting(MessageEnvelope::type).containsOnly("ReserveSeat");
        assertThat(envelopes).extracting(envelope -> codec.payload(envelope, Sample.class).name())
                .containsExactly("first", "second");
        assertThat(new String(records.getFirst().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .contains(request.context().traceId());
        assertThat(unpublished()).isZero();
        assertThat(relay.relay()).isZero();
    }

    @Test
    void theOutboxReportsWhatWaitsForKafka() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        outboxMetrics.bindTo(meters);
        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isZero();
        assertThat(meters.get(OutboxMetrics.OLDEST).gauge().value()).isZero();

        transaction.executeWithoutResult(status -> {
            outbox.enqueue(uniqueTopic(), "k", "T", new Sample("one"));
            outbox.enqueue(uniqueTopic(), "k", "T", new Sample("two"));
        });

        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isEqualTo(2);
        assertThat(meters.get(OutboxMetrics.OLDEST).gauge().value()).isGreaterThanOrEqualTo(0).isLessThan(60);
        relay.relay();
        assertThat(meters.get(OutboxMetrics.PENDING).gauge().value()).isZero();
        assertThat(meters.get(OutboxMetrics.OLDEST).gauge().value()).isZero();
    }

    @Test
    void rolledBackMessagesAreNeverPublished() {
        transaction.executeWithoutResult(status -> {
            outbox.enqueue(uniqueTopic(), "event-1", "ReserveSeat", new Sample("cancelled"));
            status.setRollbackOnly();
        });

        assertThat(relay.relay()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_messages").query(Long.class).single()).isZero();
    }

    @Test
    void aMessageThatKafkaRefusesStaysWithItsSuccessorsUntilKafkaIsBack() {
        String topic = uniqueTopic();
        transaction.executeWithoutResult(status -> {
            outbox.enqueue(topic, "event-1", "ReserveSeat", new Sample("published"));
            outbox.enqueue(topic, "event-1", "ReserveSeat", new Sample("refused"));
            outbox.enqueue(topic, "event-1", "ReleaseSeat", new Sample("waiting"));
        });
        // Kafka acknowledges the first send, then refuses everything until it is back.
        KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producerFactory);
        AtomicInteger sends = new AtomicInteger();
        AtomicBoolean down = new AtomicBoolean(true);
        @SuppressWarnings("unchecked")
        KafkaOperations<String, String> flakyKafka = mock(KafkaOperations.class);
        doAnswer(invocation -> sends.incrementAndGet() > 1 && down.get()
                ? CompletableFuture.failedFuture(new IllegalStateException("Kafka down"))
                : kafka.send(invocation.<ProducerRecord<String, String>>getArgument(0)))
                .when(flakyKafka).send(any(ProducerRecord.class));
        OutboxRelay flakyRelay = new OutboxRelay(jdbc, transaction, flakyKafka, Clock.systemUTC(), properties(false, 100));

        assertThat(flakyRelay.relay()).isEqualTo(1);
        assertThat(flakyRelay.relay()).isZero();
        assertThat(unpublished()).isEqualTo(2);

        down.set(false);
        assertThat(flakyRelay.relay()).isEqualTo(2);
        assertThat(consume(topic, 3)).extracting(record -> codec.payload(codec.decode(record.value()), Sample.class).name())
                .containsExactly("published", "refused", "waiting");
    }

    @Test
    void aRedeliveredMessageIsRecognized() {
        UUID messageId = UUID.randomUUID();

        Boolean first = transaction.execute(status -> processedMessages.markProcessed(messageId));
        Boolean redelivered = transaction.execute(status -> processedMessages.markProcessed(messageId));

        assertThat(first).isTrue();
        assertThat(redelivered).isFalse();
    }

    @Test
    void thePurgeDeletesWhatIsPastTheRetentionOnly() {
        Instant old = Instant.now().minus(Duration.ofDays(8));
        Instant recent = Instant.now().minus(Duration.ofDays(1));
        insertPublished(old);
        insertPublished(recent);
        transaction.executeWithoutResult(status -> outbox.enqueue(uniqueTopic(), "k", "T", new Sample("unpublished")));
        insertProcessed(old);
        insertProcessed(recent);

        assertThat(relay.purge()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_messages").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM processed_messages").query(Long.class).single()).isEqualTo(1);
        relay.purgeQuietly();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox_messages").query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void theBackgroundRelayPublishesUntilStopped() {
        String topic = uniqueTopic();
        // Batches of one: the relay drains the outbox batch after batch, without waiting for the next poll.
        OutboxRelay background = new OutboxRelay(jdbc, transaction, new KafkaTemplate<>(producerFactory),
                Clock.systemUTC(), properties(true, 1));
        background.start();
        try {
            assertThat(background.isRunning()).isTrue();
            transaction.executeWithoutResult(status -> {
                outbox.enqueue(topic, "event-2", "ReserveSeat", new Sample("x"));
                outbox.enqueue(topic, "event-2", "ReleaseSeat", new Sample("y"));
            });

            assertThat(consume(topic, 2)).hasSize(2);
            await().atMost(Duration.ofSeconds(10)).until(() -> unpublished() == 0);
        } finally {
            background.stop();
        }
        assertThat(background.isRunning()).isFalse();
        background.stop();
    }

    @Test
    void aMessageThatKeepsFailingEndsInTheDeadLetterTopicAfterTwoRetries() throws Exception {
        String key = UUID.randomUUID().toString();
        String envelope = codec.encode(new MessageEnvelope(UUID.randomUUID(), "ReserveSeat", Instant.now(),
                codec.toTree(new Sample("poison"))));

        kafkaTemplate.send(POISON_TOPIC, key, envelope).get();

        ConsumerRecord<String, String> dead = consumeKey(MessagingTopics.deadLetterOf(POISON_TOPIC), key);
        assertThat(dead.value()).isEqualTo(envelope);
        assertThat(poisonListener.attempts(key)).isEqualTo(3);
        await().atMost(Duration.ofSeconds(10)).until(() -> meterRegistry
                .counter(DeadLetterCounter.DEAD_LETTERS, DeadLetterCounter.TOPIC, POISON_TOPIC).count() >= 1);
    }

    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicWithoutRetry() throws Exception {
        String key = UUID.randomUUID().toString();

        kafkaTemplate.send(POISON_TOPIC, key, "not an envelope").get();

        ConsumerRecord<String, String> dead = consumeKey(MessagingTopics.deadLetterOf(POISON_TOPIC), key);
        assertThat(dead.value()).isEqualTo("not an envelope");
        assertThat(poisonListener.attempts(key)).isEqualTo(1);
    }

    private static MessagingProperties properties(boolean relayEnabled, int batchSize) {
        return new MessagingProperties(relayEnabled, Duration.ofMillis(50), batchSize, Duration.ofSeconds(5),
                Duration.ofDays(7), Duration.ofHours(1));
    }

    private static String uniqueTopic() {
        return "it.outbox." + UUID.randomUUID();
    }

    private long unpublished() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox_messages WHERE published_at IS NULL").query(Long.class).single();
    }

    private void insertPublished(Instant at) {
        jdbc.sql("""
                        INSERT INTO outbox_messages (message_id, topic, message_key, payload, created_at, published_at)
                        VALUES (:id, 't', 'k', '{}', :at, :at)
                        """)
                .param("id", UUID.randomUUID()).param("at", Timestamp.from(at)).update();
    }

    private void insertProcessed(Instant at) {
        jdbc.sql("INSERT INTO processed_messages (message_id, processed_at) VALUES (:id, :at)")
                .param("id", UUID.randomUUID()).param("at", Timestamp.from(at)).update();
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int count) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return records.size() >= count;
            });
            return records;
        }
    }

    private ConsumerRecord<String, String> consumeKey(String topic, String key) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> matching = new ArrayList<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        matching.add(record);
                    }
                });
                return !matching.isEmpty();
            });
            return matching.getFirst();
        }
    }

    /** Counts each delivery, reads the envelope, then always fails. */
    static class PoisonListener {

        private final MessageCodec codec;
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        PoisonListener(MessageCodec codec) {
            this.codec = codec;
        }

        @KafkaListener(topics = POISON_TOPIC, groupId = "it-poison")
        void on(ConsumerRecord<String, String> record) {
            attempts.computeIfAbsent(record.key(), key -> new AtomicInteger()).incrementAndGet();
            codec.decode(record.value());
            throw new IllegalStateException("Always failing");
        }

        int attempts(String key) {
            AtomicInteger count = attempts.get(key);
            return count == null ? 0 : count.get();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Containers.class)
    static class TestApplication {

        @Bean
        DataSourceInitializer messagingSchema(DataSource dataSource) {
            DataSourceInitializer initializer = new DataSourceInitializer();
            initializer.setDataSource(dataSource);
            initializer.setDatabasePopulator(new ResourceDatabasePopulator(
                    new ClassPathResource("com/kmg/eventplatform/messaging/schema-postgresql.sql")));
            return initializer;
        }

        @Bean
        PoisonListener poisonListener(MessageCodec codec) {
            return new PoisonListener(codec);
        }

        /** What the actuator provides in the services: dead letters are counted in it. */
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaTestContainer();
        }
    }
}
