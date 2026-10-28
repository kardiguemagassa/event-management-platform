package com.kmg.eventplatform.event.domain;

import java.util.UUID;

public final class EventNotRegistrableException extends EventRuleException {

    public EventNotRegistrableException(UUID eventId, EventStatus status) {
        super(eventId, "Event " + eventId + " does not accept registrations (status " + status
                + ", or it has already started)");
    }

    @Override
    public String code() {
        return "EVENT_NOT_REGISTRABLE";
    }
}
