package com.kmg.eventplatform.event.domain;

import java.util.UUID;

public final class ReservationConflictException extends EventRuleException {

    public ReservationConflictException(UUID eventId, UUID reservationId) {
        super(eventId, "Reservation " + reservationId + " already exists for another event");
    }

    @Override
    public String code() {
        return "RESERVATION_ID_CONFLICT";
    }
}
