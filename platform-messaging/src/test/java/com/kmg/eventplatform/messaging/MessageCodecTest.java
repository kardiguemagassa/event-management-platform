package com.kmg.eventplatform.messaging;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageCodecTest {

    private final MessageCodec codec = new MessageCodec(JsonMapper.builder().build());

    record Seat(UUID eventId, int count) {
    }

    @Test
    void envelopesRoundTripWithTheirPayload() {
        Seat seat = new Seat(UUID.randomUUID(), 2);
        MessageEnvelope envelope = new MessageEnvelope(UUID.randomUUID(), "ReserveSeat",
                Instant.parse("2026-11-03T18:00:00Z"), codec.toTree(seat));

        MessageEnvelope decoded = codec.decode(codec.encode(envelope));

        assertThat(decoded.messageId()).isEqualTo(envelope.messageId());
        assertThat(decoded.type()).isEqualTo("ReserveSeat");
        assertThat(decoded.occurredAt()).isEqualTo(envelope.occurredAt());
        assertThat(codec.payload(decoded, Seat.class)).isEqualTo(seat);
    }

    @Test
    void aValueThatIsNotJsonIsInvalid() {
        assertThatThrownBy(() -> codec.decode("not json"))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Not a message envelope");
    }

    @Test
    void anEnvelopeWithoutIdOrTypeIsInvalid() {
        assertThatThrownBy(() -> codec.decode("{\"type\":\"ReserveSeat\"}"))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Message envelope without id or type");
        assertThatThrownBy(() -> codec.decode("{\"messageId\":\"" + UUID.randomUUID() + "\"}"))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> codec.decode("null"))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void aPayloadOfAnotherShapeIsInvalid() {
        MessageEnvelope envelope = codec.decode(
                "{\"messageId\":\"" + UUID.randomUUID() + "\",\"type\":\"ReserveSeat\",\"payload\":{\"eventId\":\"x\"}}");

        assertThatThrownBy(() -> codec.payload(envelope, Seat.class))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessageContaining("is not a Seat");
    }
}
