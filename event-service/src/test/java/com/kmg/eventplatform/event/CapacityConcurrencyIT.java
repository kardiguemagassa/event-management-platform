package com.kmg.eventplatform.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kmg.eventplatform.event.application.CapacityOutcome;
import com.kmg.eventplatform.event.application.CapacityService;
import com.kmg.eventplatform.event.application.EventService;
import com.kmg.eventplatform.event.application.ReservationAttempt;
import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventHasReservationsException;
import com.kmg.eventplatform.event.domain.EventRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Proves the capacity guarantees under real concurrency against PostgreSQL: no overbooking, idempotent replays,
 * consistent counters. The seat commands of Kafka are handled by this same service, one transaction each.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class CapacityConcurrencyIT {

    private static final Instant START = Instant.parse("2099-06-01T18:00:00Z");

    @Autowired
    private CapacityService capacity;

    @Autowired
    private EventService events;

    @Autowired
    private EventRepository repository;

    private final UUID organizer = UUID.randomUUID();

    private UUID event(int seats, boolean published) {
        Event event = events.create(new EventDetails("Concurrency", null, "Paris", START, seats), organizer);
        if (published) {
            events.publish(event.getId(), organizer);
        }
        return event.getId();
    }

    private int reservedSeats(UUID eventId) {
        return repository.findById(eventId).orElseThrow().getReservedSeats();
    }

    /** Starts all calls at the same instant to maximize contention. */
    private static <T> List<T> concurrently(int count, Callable<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    void twentyConcurrentReservationsNeverOverbookFiveSeats() throws Exception {
        UUID eventId = event(5, true);

        List<ReservationAttempt> attempts =
                concurrently(20, () -> capacity.attemptReservation(eventId, UUID.randomUUID()));

        assertThat(attempts).filteredOn(ReservationAttempt.RESERVED::equals).hasSize(5);
        assertThat(attempts).filteredOn(ReservationAttempt.EVENT_FULL::equals).hasSize(15);
        assertThat(reservedSeats(eventId)).isEqualTo(5);
    }

    @Test
    void concurrentReplaysOfTheSameReservationTakeOneSeat() throws Exception {
        UUID eventId = event(5, true);
        UUID reservationId = UUID.randomUUID();

        List<ReservationAttempt> attempts =
                concurrently(10, () -> capacity.attemptReservation(eventId, reservationId));

        assertThat(attempts).allMatch(ReservationAttempt::isReserved);
        assertThat(attempts).filteredOn(ReservationAttempt.RESERVED::equals).hasSize(1);
        assertThat(reservedSeats(eventId)).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndKeepsCountersConsistent() {
        UUID eventId = event(5, true);
        UUID reservationId = UUID.randomUUID();
        capacity.attemptReservation(eventId, reservationId);
        assertThatThrownBy(() -> events.delete(eventId, organizer))
                .as("an event with reservations cannot be deleted")
                .isInstanceOf(EventHasReservationsException.class);

        assertThat(capacity.releaseIfReserved(eventId, reservationId)).isEqualTo(CapacityOutcome.RELEASED);
        assertThat(capacity.releaseIfReserved(eventId, reservationId)).isEqualTo(CapacityOutcome.NOT_RESERVED);

        assertThat(reservedSeats(eventId)).isZero();
        events.delete(eventId, organizer);
        assertThat(repository.existsById(eventId)).isFalse();
    }

    @Test
    void aDraftEventIsNotRegistrable() {
        UUID eventId = event(5, false);

        assertThat(capacity.attemptReservation(eventId, UUID.randomUUID()))
                .isEqualTo(ReservationAttempt.EVENT_NOT_REGISTRABLE);
        assertThat(reservedSeats(eventId)).isZero();
    }
}
