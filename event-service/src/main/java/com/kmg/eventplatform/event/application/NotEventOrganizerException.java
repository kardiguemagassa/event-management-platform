package com.kmg.eventplatform.event.application;

import java.util.UUID;

/** The caller may manage events, but not this one: it was created by another organizer. */
public class NotEventOrganizerException extends RuntimeException {

    private final UUID eventId;

    public NotEventOrganizerException(UUID eventId) {
        super("Only the organizer of event " + eventId + " can change it");
        this.eventId = eventId;
    }

    public UUID getEventId() {
        return eventId;
    }
}
