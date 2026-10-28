package com.kmg.eventplatform.event.application;

import com.kmg.eventplatform.event.domain.CapacityRepository;
import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventFullException;
import com.kmg.eventplatform.event.domain.EventNotRegistrableException;
import com.kmg.eventplatform.event.domain.EventRepository;
import com.kmg.eventplatform.event.domain.ReservationConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seat reservation used by registration-service. Both operations are idempotent per
 * reservation id, so callers can safely retry them after a timeout.
 */
@Service
@Transactional
public class CapacityService {

    private final EventRepository events;
    private final CapacityRepository capacity;
    private final Clock clock;

    public CapacityService(EventRepository events, CapacityRepository capacity, Clock clock) {
        this.events = events;
        this.capacity = capacity;
        this.clock = clock;
    }

    public CapacityOutcome reserve(UUID eventId, UUID reservationId) {
        Instant now = clock.instant();
        Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));

        // Replay first: a retried reservation gets the same answer, even if the event changed since
        if (capacity.insertReservation(reservationId, eventId, now) == 0) {
            return replayOf(eventId, reservationId);
        }
        if (!event.isRegistrable(now)) {
            throw new EventNotRegistrableException(eventId, event.getStatus());
        }
        // Throwing rolls back the transaction, including the reservation row inserted above
        if (capacity.incrementReservedSeats(eventId, now) == 0) {
            throw new EventFullException(eventId);
        }
        return CapacityOutcome.RESERVED;
    }

    public CapacityOutcome release(UUID eventId, UUID reservationId) {
        if (!events.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        if (capacity.deleteReservation(reservationId, eventId) == 0) {
            return CapacityOutcome.NOT_RESERVED;
        }
        capacity.decrementReservedSeats(eventId);
        return CapacityOutcome.RELEASED;
    }

    private CapacityOutcome replayOf(UUID eventId, UUID reservationId) {
        UUID reservedEventId = capacity.findReservedEventId(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reservation " + reservationId + " disappeared"));
        if (!reservedEventId.equals(eventId)) {
            throw new ReservationConflictException(eventId, reservationId);
        }
        return CapacityOutcome.ALREADY_RESERVED;
    }
}
