package com.kmg.eventplatform.event;

import com.kmg.eventplatform.messaging.test.KafkaTestContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka for the tests that start the whole application (the seat command listener and the outbox relay run in it).
 * Separate from {@link TestcontainersConfiguration}: repository tests need PostgreSQL only.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestcontainersConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaTestContainer();
    }
}
