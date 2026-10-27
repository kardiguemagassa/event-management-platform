package com.kmg.eventplatform.registration.application;

import java.util.UUID;

public final class DuplicateRegistrationException extends RegistrationFailureException {

    public DuplicateRegistrationException(UUID eventId, UUID userId) {
        super("ALREADY_REGISTERED", "User " + userId + " is already registered to event " + eventId);
    }
}
