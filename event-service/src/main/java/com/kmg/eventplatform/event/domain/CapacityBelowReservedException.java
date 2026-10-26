package com.kmg.eventplatform.event.domain;

import java.util.UUID;

public class CapacityBelowReservedException extends EventRuleException {

    public CapacityBelowReservedException(UUID eventId, int requestedCapacity, int reservedSeats) {
        super(eventId, "Capacity " + requestedCapacity + " is below the " + reservedSeats
                + " seats already reserved for event " + eventId);
    }

    @Override
    public String code() {
        return "CAPACITY_BELOW_RESERVED";
    }
}
