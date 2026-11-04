package com.kmg.eventplatform.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A message sent to its dead letter topic is a request left unanswered: logged with its position (to find it in the
 * topic) and counted by source topic, a small and bounded set of values. Without a meter registry, logged only.
 */
class DeadLetterCounter {

    static final String DEAD_LETTERS = "messaging.dead.letters";
    static final String TOPIC = "topic";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterCounter.class);

    private final ObjectProvider<MeterRegistry> registry;

    DeadLetterCounter(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    void sent(ConsumerRecord<?, ?> failed, Exception exception) {
        log.error("Message {}-{}@{} sent to {}: {}", failed.topic(), failed.partition(), failed.offset(),
                MessagingTopics.deadLetterOf(failed.topic()), exception.toString());
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            Counter.builder(DEAD_LETTERS)
                    .description("Messages sent to a dead letter topic, by the topic they came from")
                    .tag(TOPIC, failed.topic())
                    .register(meters)
                    .increment();
        }
    }
}
