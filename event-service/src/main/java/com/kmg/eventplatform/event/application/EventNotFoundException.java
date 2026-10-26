package com.kmg.eventplatform.event.application;

import java.util.UUID;

public class EventNotFoundException extends RuntimeException {

    private final UUID eventId;

    public EventNotFoundException(UUID eventId) {
        super("Event " + eventId + " not found");
        this.eventId = eventId;
    }

    public UUID getEventId() {
        return eventId;
    }
}
