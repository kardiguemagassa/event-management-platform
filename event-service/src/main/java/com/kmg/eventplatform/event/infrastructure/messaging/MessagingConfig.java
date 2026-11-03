package com.kmg.eventplatform.event.infrastructure.messaging;

import com.kmg.eventplatform.messaging.MessagingTopics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * The topics this service reads and writes, created at startup if missing (automatic creation is off on the
 * broker). registration-service declares the same ones: whichever starts first creates them.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfig {

    @Bean
    KafkaAdmin.NewTopics seatCommandsTopic() {
        return MessagingTopics.withDeadLetter(SeatMessages.COMMANDS_TOPIC, SeatMessages.PARTITIONS);
    }

    @Bean
    KafkaAdmin.NewTopics seatRepliesTopic() {
        return MessagingTopics.withDeadLetter(SeatMessages.REPLIES_TOPIC, SeatMessages.PARTITIONS);
    }
}
