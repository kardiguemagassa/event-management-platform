package com.kmg.eventplatform.messaging;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * What travels on a topic: the identity of the message (idempotency key of its consumers), what it means, when it was
 * written, and its content. The Kafka key is the business key that orders the messages of one aggregate.
 *
 * @param messageId  unique per message, kept across redeliveries
 * @param type       what the message means ({@code ReserveSeat}, {@code SeatReserved}...), how consumers dispatch it
 * @param occurredAt when the business change that wrote it was committed
 * @param payload    the content, read with {@link MessageCodec#payload}
 */
public record MessageEnvelope(UUID messageId, String type, Instant occurredAt, JsonNode payload) {
}
