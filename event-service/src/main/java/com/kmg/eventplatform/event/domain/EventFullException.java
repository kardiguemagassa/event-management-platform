package com.kmg.eventplatform.event.domain;

import java.util.UUID;

public final class EventFullException extends EventRuleException {

    public EventFullException(UUID eventId) {
        super(eventId, "Event " + eventId + " is full");
    }

    @Override
    public String code() {
        return "EVENT_FULL";
    }
}
