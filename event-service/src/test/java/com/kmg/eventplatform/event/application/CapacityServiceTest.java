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
import com.kmg.eventplatform.event.domain.EventFullException;
import com.kmg.eventplatform.event.domain.EventNotRegistrableException;
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
                new EventDetails("Meetup", null, "Paris", NOW.plus(Duration.ofDays(30)), 10), NOW);
        if (published) {
            event.publish(NOW);
        }
        when(events.findById(event.getId())).thenReturn(Optional.of(event));
        return event;
    }

    @Nested
    class Reserve {

        @Test
        void takesASeat() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(1);
            when(capacity.incrementReservedSeats(event.getId(), NOW)).thenReturn(1);

            assertThat(service.reserve(event.getId(), RESERVATION)).isEqualTo(CapacityOutcome.RESERVED);
        }

        @Test
        void replayDoesNotTakeASecondSeat() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(0);
            when(capacity.findReservedEventId(RESERVATION)).thenReturn(Optional.of(event.getId()));

            assertThat(service.reserve(event.getId(), RESERVATION)).isEqualTo(CapacityOutcome.ALREADY_RESERVED);
            verify(capacity, never()).incrementReservedSeats(any(), any());
        }

        @Test
        void reservationIdUsedForAnotherEventIsAConflict() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(0);
            when(capacity.findReservedEventId(RESERVATION)).thenReturn(Optional.of(UUID.randomUUID()));

            assertThatThrownBy(() -> service.reserve(event.getId(), RESERVATION))
                    .isInstanceOf(ReservationConflictException.class);
        }

        @Test
        void draftEventIsNotRegistrable() {
            Event event = existingEvent(false);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(1);

            assertThatThrownBy(() -> service.reserve(event.getId(), RESERVATION))
                    .isInstanceOf(EventNotRegistrableException.class);
            verify(capacity, never()).incrementReservedSeats(any(), any());
        }

        @Test
        void fullEventIsRejected() {
            Event event = existingEvent(true);
            when(capacity.insertReservation(RESERVATION, event.getId(), NOW)).thenReturn(1);
            when(capacity.incrementReservedSeats(event.getId(), NOW)).thenReturn(0);

            assertThatThrownBy(() -> service.reserve(event.getId(), RESERVATION))
                    .isInstanceOf(EventFullException.class);
        }

        @Test
        void unknownEventIsNotFound() {
            UUID eventId = UUID.randomUUID();
            when(events.findById(eventId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.reserve(eventId, RESERVATION))
                    .isInstanceOf(EventNotFoundException.class);
            verify(capacity, never()).insertReservation(any(), any(), any());
        }
    }

    @Nested
    class Release {

        private final UUID eventId = UUID.randomUUID();

        @Test
        void freesTheSeat() {
            when(events.existsById(eventId)).thenReturn(true);
            when(capacity.deleteReservation(RESERVATION, eventId)).thenReturn(1);

            assertThat(service.release(eventId, RESERVATION)).isEqualTo(CapacityOutcome.RELEASED);
            verify(capacity).decrementReservedSeats(eventId);
        }

        @Test
        void replayOrUnknownReservationIsANoOp() {
            when(events.existsById(eventId)).thenReturn(true);
            when(capacity.deleteReservation(RESERVATION, eventId)).thenReturn(0);

            assertThat(service.release(eventId, RESERVATION)).isEqualTo(CapacityOutcome.NOT_RESERVED);
            verify(capacity, never()).decrementReservedSeats(any());
        }

        @Test
        void unknownEventIsNotFound() {
            when(events.existsById(eventId)).thenReturn(false);

            assertThatThrownBy(() -> service.release(eventId, RESERVATION))
                    .isInstanceOf(EventNotFoundException.class);
        }
    }
}
