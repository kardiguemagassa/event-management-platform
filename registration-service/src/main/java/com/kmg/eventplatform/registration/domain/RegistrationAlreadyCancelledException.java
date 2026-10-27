package com.kmg.eventplatform.registration.domain;

import java.util.UUID;

public final class RegistrationAlreadyCancelledException extends RegistrationRuleException {

    public RegistrationAlreadyCancelledException(UUID registrationId) {
        super("REGISTRATION_ALREADY_CANCELLED", "Registration " + registrationId + " is already cancelled");
    }
}
