package com.kmg.eventplatform.registration.domain;

/**
 * Lifecycle of a registration in the seat saga: requested (PENDING), then CONFIRMED when event-service reserved the
 * seat or REJECTED when it refused; PENDING and CONFIRMED are active, and can be CANCELLED by the attendee.
 */
public enum RegistrationStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCELLED;

    /** Holds (or is about to hold) a seat: at most one per attendee and event. */
    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }
}
