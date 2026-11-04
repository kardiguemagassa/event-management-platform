package com.kmg.eventplatform.registration.domain;

import java.time.Instant;
import java.util.UUID;

/** Test fixtures: registrations in a given state of the seat saga. */
public final class Registrations {

    private Registrations() {
    }

    /** Requested, then confirmed by event-service. */
    public static Registration confirmed(UUID id, UUID eventId, UUID userId, Instant createdAt) {
        Registration registration = Registration.request(id, eventId, userId, createdAt);
        registration.confirmSeat();
        return registration;
    }
}
