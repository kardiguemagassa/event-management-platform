package com.kmg.eventplatform.registration.application;

import java.util.UUID;

public final class RegistrationNotFoundException extends RegistrationFailureException {

    public RegistrationNotFoundException(UUID registrationId) {
        super("REGISTRATION_NOT_FOUND", "Registration " + registrationId + " not found");
    }
}
