package com.kmg.eventplatform.registration.application;

import java.util.UUID;

public final class EventFullException extends RegistrationFailureException {

    public EventFullException(UUID eventId) {
        super("EVENT_FULL", "Event " + eventId + " has no seat left");
    }
}
