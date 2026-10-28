package com.kmg.eventplatform.registration.application;

import java.util.UUID;

public final class EventNotFoundException extends RegistrationFailureException {

    public EventNotFoundException(UUID eventId) {
        super("EVENT_NOT_FOUND", "Event " + eventId + " not found");
    }
}
