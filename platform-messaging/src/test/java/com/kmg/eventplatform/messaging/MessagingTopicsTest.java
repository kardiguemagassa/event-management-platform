package com.kmg.eventplatform.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingTopicsTest {

    @Test
    void aTopicComesWithItsDeadLetterTopic() {
        assertThat(MessagingTopics.withDeadLetter("registration.seat-commands", 3)).isNotNull();
        assertThat(MessagingTopics.topicAndDeadLetter("registration.seat-commands", 3))
                .extracting(NewTopic::name, NewTopic::numPartitions)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("registration.seat-commands", 3),
                        org.assertj.core.groups.Tuple.tuple("registration.seat-commands.dlt", 1));
    }
}
