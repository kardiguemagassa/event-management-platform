package com.kmg.eventplatform.messaging;

import io.micrometer.tracing.Tracer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Applied to every service depending on this module. The service still declares its topics
 * ({@link MessagingTopics#withDeadLetter}), creates the tables in a migration of its own (reference:
 * {@code com/kmg/eventplatform/messaging/schema-postgresql.sql}) and configures Kafka with the standard
 * {@code spring.kafka.*} properties.
 */
@AutoConfiguration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    /** Two retries one second apart: a transient failure (database restart...) passes, a bug does not loop. */
    static final long RETRY_INTERVAL_MS = 1_000;
    static final long RETRIES = 2;

    @Bean
    @ConditionalOnMissingBean
    MessageCodec messageCodec(JsonMapper jsonMapper) {
        return new MessageCodec(jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    Outbox outbox(DataSource dataSource, MessageCodec codec, ObjectProvider<Tracer> tracer,
                  ObjectProvider<Clock> clock) {
        return new Outbox(JdbcClient.create(dataSource), codec, new TraceContextReader(tracer),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedMessages processedMessages(DataSource dataSource, ObjectProvider<Clock> clock) {
        return new ProcessedMessages(JdbcClient.create(dataSource), clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * A template of its own, built on the producer of the service (idempotent, {@code acks=all}: the Kafka client's
     * defaults): the trace header it sends is the one stored with the message, not the relay's.
     */
    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(DataSource dataSource, PlatformTransactionManager transactionManager,
                            ProducerFactory<String, String> producerFactory, ObjectProvider<Clock> clock,
                            MessagingProperties properties) {
        return new OutboxRelay(JdbcClient.create(dataSource), new TransactionTemplate(transactionManager),
                new KafkaTemplate<>(producerFactory), clock.getIfAvailable(Clock::systemUTC), properties);
    }

    /**
     * Applied by Spring Boot to the listeners: a failing message is retried, then sent to the dead letter topic of
     * its topic ({@link MessagingTopics#deadLetterOf}) with the exception in its headers, and the partition moves on.
     * A message that cannot be read is not retried.
     */
    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler messagingErrorHandler(KafkaTemplate<?, ?> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (failed, exception) -> new TopicPartition(MessagingTopics.deadLetterOf(failed.topic()), -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
        handler.addNotRetryableExceptions(InvalidMessageException.class, JacksonException.class);
        return handler;
    }
}
