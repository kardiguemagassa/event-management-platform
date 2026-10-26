package com.kmg.eventplatform.event.domain;

import java.util.UUID;

/** A business rule of the event domain was violated. Carries a stable machine-readable code. */
public abstract class EventRuleException extends RuntimeException {

    private final UUID eventId;

    protected EventRuleException(UUID eventId, String message) {
        super(message);
        this.eventId = eventId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public abstract String code();
}
