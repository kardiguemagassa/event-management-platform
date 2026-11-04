package com.kmg.eventplatform.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeadLetterCounterTest {

    private final ConsumerRecord<String, String> failed =
            new ConsumerRecord<>("registration.seat-commands", 2, 41L, "key", "value");

    @Test
    void deadLettersAreCountedByTheTopicTheyCameFrom() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new DeadLetterCounter(provider(meters)).sent(failed, new InvalidMessageException("broken"));

        assertThat(meters.counter(DeadLetterCounter.DEAD_LETTERS, DeadLetterCounter.TOPIC,
                "registration.seat-commands").count()).isEqualTo(1);
    }

    @Test
    void withoutARegistryTheyAreLoggedOnly() {
        assertThatCode(() -> new DeadLetterCounter(provider(null)).sent(failed, new IllegalStateException("bug")))
                .doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }
}
