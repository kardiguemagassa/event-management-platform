package com.kmg.eventplatform.event.application;

/**
 * Answer to a seat request when a refusal is a result, not a failure: the messaging path replies with it instead of
 * throwing (an exception would roll back the reply written in the same transaction).
 */
public enum ReservationAttempt {
    RESERVED(null),
    /** Replay of a reservation already held: the same answer as the first time. */
    ALREADY_RESERVED(null),
    EVENT_NOT_FOUND("EVENT_NOT_FOUND"),
    EVENT_NOT_REGISTRABLE("EVENT_NOT_REGISTRABLE"),
    EVENT_FULL("EVENT_FULL");

    private final String rejectionCode;

    ReservationAttempt(String rejectionCode) {
        this.rejectionCode = rejectionCode;
    }

    public boolean isReserved() {
        return rejectionCode == null;
    }

    /**
     * @return the code of the refusal, the one of the matching HTTP problem; {@code null} when reserved
     */
    public String rejectionCode() {
        return rejectionCode;
    }
}
