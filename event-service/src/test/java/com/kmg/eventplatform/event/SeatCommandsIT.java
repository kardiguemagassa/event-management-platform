package com.kmg.eventplatform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventRepository;
import com.kmg.eventplatform.event.infrastructure.messaging.SeatMessages;
import com.kmg.eventplatform.event.infrastructure.messaging.SeatMessages.SeatCommand;
import com.kmg.eventplatform.event.infrastructure.messaging.SeatMessages.SeatRejected;
import com.kmg.eventplatform.messaging.MessageCodec;
import com.kmg.eventplatform.messaging.MessageEnvelope;
import com.kmg.eventplatform.messaging.MessagingTopics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seat commands of the saga against a real Kafka and PostgreSQL: registration-service's side is played by the
 * test, which writes commands and reads the replies the outbox relay publishes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class SeatCommandsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private EventRepository events;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private ConsumerFactory<String, String> consumerFactory;
    @Autowired
    private MessageCodec codec;
    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void aSeatIsReservedThenRefusedOnceTheEventIsFull() throws Exception {
        UUID eventId = publishedEvent(1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        send(SeatMessages.RESERVE_SEAT, eventId, first, UUID.randomUUID());
        send(SeatMessages.RESERVE_SEAT, eventId, second, UUID.randomUUID());

        assertThat(replyTo(first).type()).isEqualTo(SeatMessages.SEAT_RESERVED);
        MessageEnvelope refusal = replyTo(second);
        assertThat(refusal.type()).isEqualTo(SeatMessages.SEAT_REJECTED);
        assertThat(codec.payload(refusal, SeatRejected.class).reason()).isEqualTo("EVENT_FULL");
        assertThat(reservedSeats(eventId)).isEqualTo(1);
    }

    @Test
    void aReserveDeliveredAgainAfterItsReleaseDoesNotTakeTheSeatBack() throws Exception {
        UUID eventId = publishedEvent(5);
        UUID reservation = UUID.randomUUID();
        UUID reserveMessage = UUID.randomUUID();

        send(SeatMessages.RESERVE_SEAT, eventId, reservation, reserveMessage);
        assertThat(replyTo(reservation).type()).isEqualTo(SeatMessages.SEAT_RESERVED);
        send(SeatMessages.RELEASE_SEAT, eventId, reservation, UUID.randomUUID());
        send(SeatMessages.RESERVE_SEAT, eventId, reservation, reserveMessage);
        // Same key, same partition: once this one is answered, the commands before it have been handled.
        UUID marker = UUID.randomUUID();
        send(SeatMessages.RESERVE_SEAT, eventId, marker, UUID.randomUUID());

        assertThat(replyTo(marker).type()).isEqualTo(SeatMessages.SEAT_RESERVED);
        assertThat(reservedSeats(eventId)).as("only the marker holds a seat").isEqualTo(1);
    }

    @Test
    void anUnknownEventIsRefused() throws Exception {
        UUID reservation = UUID.randomUUID();

        send(SeatMessages.RESERVE_SEAT, UUID.randomUUID(), reservation, UUID.randomUUID());

        MessageEnvelope refusal = replyTo(reservation);
        assertThat(refusal.type()).isEqualTo(SeatMessages.SEAT_REJECTED);
        assertThat(codec.payload(refusal, SeatRejected.class).reason()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void aCommandOfUnknownTypeGoesToTheDeadLetterTopic() throws Exception {
        UUID eventId = UUID.randomUUID();

        send("TakeAllSeats", eventId, UUID.randomUUID(), UUID.randomUUID());

        ConsumerRecord<String, String> dead =
                first(MessagingTopics.deadLetterOf(SeatMessages.COMMANDS_TOPIC), eventId.toString());
        assertThat(codec.decode(dead.value()).type()).isEqualTo("TakeAllSeats");
    }

    private UUID publishedEvent(int capacity) {
        Instant now = Instant.now();
        Event event = Event.create(
                new EventDetails("Saga", null, "Paris", now.plus(Duration.ofDays(30)), capacity), UUID.randomUUID(), now);
        event.publish(now);
        return events.save(event).getId();
    }

    private int reservedSeats(UUID eventId) {
        return events.findById(eventId).orElseThrow().getReservedSeats();
    }

    private void send(String type, UUID eventId, UUID reservationId, UUID messageId) throws Exception {
        String envelope = codec.encode(new MessageEnvelope(messageId, type, Instant.now(),
                jsonMapper.valueToTree(new SeatCommand(eventId, reservationId))));
        kafka.send(SeatMessages.COMMANDS_TOPIC, eventId.toString(), envelope).get(10, TimeUnit.SECONDS);
    }

    /** The reply about a reservation, read as a refusal (a reserved reply has no reason). */
    private MessageEnvelope replyTo(UUID reservationId) {
        AtomicReference<MessageEnvelope> reply = new AtomicReference<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(SeatMessages.REPLIES_TOPIC));
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    MessageEnvelope envelope = codec.decode(record.value());
                    if (reservationId.equals(codec.payload(envelope, SeatRejected.class).reservationId())) {
                        reply.set(envelope);
                    }
                }
                return reply.get() != null;
            });
        }
        return reply.get();
    }

    private ConsumerRecord<String, String> first(String topic, String key) {
        AtomicReference<ConsumerRecord<String, String>> found = new AtomicReference<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) {
                        found.set(record);
                    }
                }
                return found.get() != null;
            });
        }
        return found.get();
    }
}
