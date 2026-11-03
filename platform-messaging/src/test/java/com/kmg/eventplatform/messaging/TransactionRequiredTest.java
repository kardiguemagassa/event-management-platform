package com.kmg.eventplatform.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Outside a transaction, a message could be written without its business change, or the reverse: refused.
 */
class TransactionRequiredTest {

    private final JdbcClient jdbc = mock(JdbcClient.class);

    @Test
    void theOutboxIsWrittenInATransactionOnly() {
        Outbox outbox = new Outbox(jdbc, new MessageCodec(JsonMapper.builder().build()),
                mock(TraceContextReader.class), Clock.systemUTC());

        assertThatThrownBy(() -> outbox.enqueue("topic", "key", "Type", "payload"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Writing to the outbox must run in the transaction of the business change");
        verifyNoInteractions(jdbc);
    }

    @Test
    void aMessageWithoutTopicKeyTypeOrPayloadIsRefused() {
        Outbox outbox = new Outbox(jdbc, new MessageCodec(JsonMapper.builder().build()),
                mock(TraceContextReader.class), Clock.systemUTC());

        assertThatThrownBy(() -> outbox.enqueue(null, "key", "Type", "p")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.enqueue("t", null, "Type", "p")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.enqueue("t", "key", null, "p")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.enqueue("t", "key", "Type", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void processedMessagesAreRecordedInATransactionOnly() {
        ProcessedMessages processed = new ProcessedMessages(jdbc, Clock.systemUTC());

        assertThatThrownBy(() -> processed.markProcessed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recording a processed message must run in the transaction of the business change");
        verifyNoInteractions(jdbc);
    }
}
