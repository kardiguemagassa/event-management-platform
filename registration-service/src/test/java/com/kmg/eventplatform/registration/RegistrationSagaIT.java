package com.kmg.eventplatform.registration;

import com.kmg.eventplatform.messaging.MessageCodec;
import com.kmg.eventplatform.messaging.MessageEnvelope;
import com.kmg.eventplatform.registration.application.RegistrationService;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages.SeatCommand;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages.SeatReply;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The saga from registration-service's side, against a real Kafka and PostgreSQL: event-service is played by the
 * test, which reads the seat commands the outbox relay publishes and writes the replies.
 */
@SpringBootTest(properties = "eureka.client.enabled=false")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class RegistrationSagaIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private RegistrationService service;
    @Autowired
    private RegistrationRepository repository;
    @Autowired
    private KafkaTemplate<String, String> kafka;
    @Autowired
    private ConsumerFactory<String, String> consumerFactory;
    @Autowired
    private MessageCodec codec;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private DataSource dataSource;

    @Test
    void aReservedSeatConfirmsTheRegistration() throws Exception {
        Registration registration = service.register(UUID.randomUUID(), UUID.randomUUID());
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.PENDING);

        assertThat(commandsFor(registration, 1)).containsExactly(SeatMessages.RESERVE_SEAT);
        reply(SeatMessages.SEAT_RESERVED, registration, null);

        await().atMost(TIMEOUT).until(() -> status(registration) == RegistrationStatus.CONFIRMED);
    }

    @Test
    void aRefusedSeatRejectsTheRegistrationWithItsReason() throws Exception {
        Registration registration = service.register(UUID.randomUUID(), UUID.randomUUID());

        commandsFor(registration, 1);
        reply(SeatMessages.SEAT_REJECTED, registration, "EVENT_FULL");

        await().atMost(TIMEOUT).until(() -> status(registration) == RegistrationStatus.REJECTED);
        assertThat(repository.findById(registration.getId()).orElseThrow().getRejectionReason()).isEqualTo("EVENT_FULL");
    }

    @Test
    void aRegistrationCancelledWhileWaitingReleasesItsSeatAndIgnoresTheLateReservation() throws Exception {
        Registration registration = service.register(UUID.randomUUID(), UUID.randomUUID());
        service.cancel(registration.getId(), registration.getUserId());

        assertThat(commandsFor(registration, 2)).containsExactly(SeatMessages.RESERVE_SEAT, SeatMessages.RELEASE_SEAT);
        UUID lateReply = reply(SeatMessages.SEAT_RESERVED, registration, null);

        await().atMost(TIMEOUT).until(() -> processed(lateReply));
        assertThat(status(registration)).isEqualTo(RegistrationStatus.CANCELLED);
    }

    private RegistrationStatus status(Registration registration) {
        return repository.findById(registration.getId()).orElseThrow().getStatus();
    }

    private boolean processed(UUID messageId) {
        return JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM processed_messages WHERE message_id = :id")
                .param("id", messageId)
                .query(Long.class).single() == 1;
    }

    /** The reply event-service would write, keyed by the event like every seat message. */
    private UUID reply(String type, Registration registration, String reason) throws Exception {
        UUID messageId = UUID.randomUUID();
        String envelope = codec.encode(new MessageEnvelope(messageId, type, Instant.now(),
                jsonMapper.valueToTree(new SeatReply(registration.getEventId(), registration.getId(), reason))));
        kafka.send(SeatMessages.REPLIES_TOPIC, registration.getEventId().toString(), envelope)
                .get(10, TimeUnit.SECONDS);
        return messageId;
    }

    /** The types of the seat commands published for a registration, in order. */
    private List<String> commandsFor(Registration registration, int count) {
        List<String> types = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("it-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(SeatMessages.COMMANDS_TOPIC));
            await().atMost(TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    MessageEnvelope envelope = codec.decode(record.value());
                    SeatCommand command = codec.payload(envelope, SeatCommand.class);
                    if (registration.getId().equals(command.reservationId())) {
                        assertThat(record.key()).isEqualTo(registration.getEventId().toString());
                        types.add(envelope.type());
                    }
                }
                return types.size() >= count;
            });
        }
        return types;
    }
}
