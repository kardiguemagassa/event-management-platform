package com.kmg.eventplatform.event.domain;

import java.util.UUID;

public final class EventHasReservationsException extends EventRuleException {

    public EventHasReservationsException(UUID eventId, int reservedSeats) {
        super(eventId, "Event " + eventId + " has " + reservedSeats + " reservations: cancel it instead of deleting it");
    }

    @Override
    public String code() {
        return "EVENT_HAS_RESERVATIONS";
    }
}
