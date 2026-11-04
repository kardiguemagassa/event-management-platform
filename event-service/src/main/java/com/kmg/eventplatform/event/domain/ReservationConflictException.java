package com.kmg.eventplatform.event.domain;

import java.util.UUID;

/**
 * A reservation id already used for another event: registration ids are unique, so this is a bug, not a business
 * answer. The seat command fails, is retried, then lands in the dead letter topic.
 */
public final class ReservationConflictException extends IllegalStateException {

    public ReservationConflictException(UUID eventId, UUID reservationId) {
        super("Reservation " + reservationId + " already exists for another event than " + eventId);
    }
}
