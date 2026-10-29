package com.kmg.eventplatform.registration.application;

import java.util.UUID;

/** Only the organizer of an event may see who registered to it. */
public final class NotEventOrganizerException extends RegistrationFailureException {

    public NotEventOrganizerException(UUID eventId) {
        super("NOT_EVENT_ORGANIZER", "Only the organizer of event " + eventId + " can see its registrations");
    }
}
