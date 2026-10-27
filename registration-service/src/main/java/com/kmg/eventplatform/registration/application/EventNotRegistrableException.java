package com.kmg.eventplatform.registration.application;

import java.util.UUID;

public final class EventNotRegistrableException extends RegistrationFailureException {

    public EventNotRegistrableException(UUID eventId) {
        super("EVENT_NOT_REGISTRABLE", "Event " + eventId + " does not accept registrations");
    }
}
