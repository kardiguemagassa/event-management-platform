package com.kmg.eventplatform.event.application;

import com.kmg.eventplatform.event.domain.CapacityRepository;
import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventFullException;
import com.kmg.eventplatform.event.domain.EventNotRegistrableException;
import com.kmg.eventplatform.event.domain.EventRepository;
import com.kmg.eventplatform.event.domain.ReservationConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seat reservation for registration-service, over HTTP (exceptions mapped to problems) or messaging (refusals as
 * results). Both operations are idempotent per reservation id, so callers can safely retry them.
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

    /**
     * HTTP path: a refusal is an exception, mapped to its problem.
     */
    public CapacityOutcome reserve(UUID eventId, UUID reservationId) {
        return switch (attemptReservation(eventId, reservationId)) {
            case RESERVED -> CapacityOutcome.RESERVED;
            case ALREADY_RESERVED -> CapacityOutcome.ALREADY_RESERVED;
            case EVENT_NOT_FOUND -> throw new EventNotFoundException(eventId);
            case EVENT_NOT_REGISTRABLE -> throw new EventNotRegistrableException(eventId,
                    events.findById(eventId).map(Event::getStatus).orElse(null));
            case EVENT_FULL -> throw new EventFullException(eventId);
        };
    }

    /**
     * Messaging path: a refusal is an answer, and nothing is left behind (no reservation row, no seat taken).
     *
     * @throws ReservationConflictException if the reservation id is already used for another event
     */
    public ReservationAttempt attemptReservation(UUID eventId, UUID reservationId) {
        Instant now = clock.instant();
        Optional<Event> event = events.findById(eventId);
        if (event.isEmpty()) {
            return ReservationAttempt.EVENT_NOT_FOUND;
        }
        // Replay first: a retried reservation gets the same answer, even if the event changed since
        if (capacity.insertReservation(reservationId, eventId, now) == 0) {
            return replayOf(eventId, reservationId);
        }
        if (!event.get().isRegistrable(now)) {
            capacity.deleteReservation(reservationId, eventId);
            return ReservationAttempt.EVENT_NOT_REGISTRABLE;
        }
        if (capacity.incrementReservedSeats(eventId, now) == 0) {
            capacity.deleteReservation(reservationId, eventId);
            return ReservationAttempt.EVENT_FULL;
        }
        return ReservationAttempt.RESERVED;
    }

    public CapacityOutcome release(UUID eventId, UUID reservationId) {
        if (!events.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        return releaseIfReserved(eventId, reservationId);
    }

    /**
     * Messaging path: an unknown event has no reservation to release, so this is a no-op too (an event with
     * reservations cannot be deleted).
     */
    public CapacityOutcome releaseIfReserved(UUID eventId, UUID reservationId) {
        if (capacity.deleteReservation(reservationId, eventId) == 0) {
            return CapacityOutcome.NOT_RESERVED;
        }
        capacity.decrementReservedSeats(eventId);
        return CapacityOutcome.RELEASED;
    }

    private ReservationAttempt replayOf(UUID eventId, UUID reservationId) {
        UUID reservedEventId = capacity.findReservedEventId(reservationId)
                .orElseThrow(() -> new IllegalStateException("Reservation " + reservationId + " disappeared"));
        if (!reservedEventId.equals(eventId)) {
            throw new ReservationConflictException(eventId, reservationId);
        }
        return ReservationAttempt.ALREADY_RESERVED;
    }
}
