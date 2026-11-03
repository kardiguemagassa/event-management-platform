package com.kmg.eventplatform.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.List;

/**
 * Topics are declared by the services that own them (created at startup by Spring Kafka), each with its dead letter
 * topic: where a message goes when its consumer keeps failing on it.
 */
public final class MessagingTopics {

    public static final String DEAD_LETTER_SUFFIX = ".dlt";

    private MessagingTopics() {
    }

    /**
     * The bean a service declares for a topic it owns.
     */
    public static KafkaAdmin.NewTopics withDeadLetter(String topic, int partitions) {
        return new KafkaAdmin.NewTopics(topicAndDeadLetter(topic, partitions).toArray(NewTopic[]::new));
    }

    /**
     * The replication factor is the broker's default: one in development, more in production.
     */
    static List<NewTopic> topicAndDeadLetter(String topic, int partitions) {
        return List.of(
                TopicBuilder.name(topic).partitions(partitions).build(),
                TopicBuilder.name(deadLetterOf(topic)).partitions(1).build());
    }

    public static String deadLetterOf(String topic) {
        return topic + DEAD_LETTER_SUFFIX;
    }
}
