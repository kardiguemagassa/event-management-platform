package com.kmg.eventplatform.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kmg.eventplatform.event.domain.CapacityRepository;
import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventRepository;
import com.kmg.eventplatform.event.domain.ReservationConflictException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CapacityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID RESERVATION = UUID.randomUUID();

    @Mock
    private EventRepository events;

    @Mock
    private CapacityRepository capacity;

    private CapacityService service;

    @BeforeEach
    void setUp() {
        service = new CapacityService(events, capacity, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Event existingEvent(boolean published) {
        Event event = Event.create(
                new EventDetails("Meetup", null, "Paris", NOW.plus(Duration.ofDays(30)), 10), UUID.randomUUID(), NOW);
        if (published) {
            event.publish(NOW);
        }
        when(events.findById(event.getId())).thenReturn(Optional.of(event));
        return event;
    }

    /** Refusals are answers, and leave nothing behind. */
    @Nested
    class Reserve {

        @Test
        void takesASeat() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(1);
            when(capacity.incrementReservedSeats(event.getId(), NOW)).thenReturn(1);

            ReservationAttempt attempt = service.attemptReservation(event.getId(), RESERVATION);

            assertThat(attempt).isEqualTo(ReservationAttempt.RESERVED);
            assertThat(attempt.isReserved()).isTrue();
            assertThat(attempt.rejectionCode()).isNull();
        }

        @Test
        void replayDoesNotTakeASecondSeat() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(0);
            when(capacity.findReservedEventId(RESERVATION)).thenReturn(Optional.of(event.getId()));

            ReservationAttempt replay = service.attemptReservation(event.getId(), RESERVATION);

            assertThat(replay).isEqualTo(ReservationAttempt.ALREADY_RESERVED);
            assertThat(replay.isReserved()).isTrue();
            verify(capacity, never()).incrementReservedSeats(any(), any());
        }

        @Test
        void reservationIdUsedForAnotherEventIsABug() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(0);
            when(capacity.findReservedEventId(RESERVATION)).thenReturn(Optional.of(UUID.randomUUID()));

            assertThatThrownBy(() -> service.attemptReservation(event.getId(), RESERVATION))
                    .isInstanceOf(ReservationConflictException.class)
                    .hasMessageContaining(RESERVATION.toString());
        }

        @Test
        void aDraftEventRefusesAndKeepsNoReservation() {
            Event event = existingEvent(false);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(1);

            ReservationAttempt attempt = service.attemptReservation(event.getId(), RESERVATION);

            assertThat(attempt).isEqualTo(ReservationAttempt.EVENT_NOT_REGISTRABLE);
            assertThat(attempt.rejectionCode()).isEqualTo("EVENT_NOT_REGISTRABLE");
            verify(capacity, never()).incrementReservedSeats(any(), any());
            verify(capacity).deleteReservation(RESERVATION, event.getId());
        }

        @Test
        void aFullEventRefusesAndKeepsNoReservation() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(1);
            when(capacity.incrementReservedSeats(event.getId(), NOW)).thenReturn(0);

            ReservationAttempt attempt = service.attemptReservation(event.getId(), RESERVATION);

            assertThat(attempt).isEqualTo(ReservationAttempt.EVENT_FULL);
            assertThat(attempt.isReserved()).isFalse();
            assertThat(attempt.rejectionCode()).isEqualTo("EVENT_FULL");
            verify(capacity).deleteReservation(RESERVATION, event.getId());
        }

        @Test
        void anUnknownEventRefusesWithoutWriting() {
            UUID eventId = UUID.randomUUID();
            when(events.findById(eventId)).thenReturn(Optional.empty());

            assertThat(service.attemptReservation(eventId, RESERVATION)).isEqualTo(ReservationAttempt.EVENT_NOT_FOUND);
            verify(capacity, never()).insertReservation(any(), any(), any());
        }
    }

    @Nested
    class Release {

        private final UUID eventId = UUID.randomUUID();

        @Test
        void freesTheSeat() {
            when(capacity.deleteReservation(RESERVATION, eventId)).thenReturn(1);

            assertThat(service.releaseIfReserved(eventId, RESERVATION)).isEqualTo(CapacityOutcome.RELEASED);
            verify(capacity).decrementReservedSeats(eventId);
        }

        @Test
        void aReplayOrAnUnknownReservationIsANoOpWhateverTheEvent() {
            when(capacity.deleteReservation(RESERVATION, eventId)).thenReturn(0);

            assertThat(service.releaseIfReserved(eventId, RESERVATION)).isEqualTo(CapacityOutcome.NOT_RESERVED);
            verify(capacity, never()).decrementReservedSeats(any());
            verify(events, never()).existsById(any());
        }
    }
}
