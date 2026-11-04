package com.kmg.eventplatform.registration.domain;

import java.util.UUID;

public final class RegistrationRejectedException extends RegistrationRuleException {

    public RegistrationRejectedException(UUID registrationId) {
        super("REGISTRATION_REJECTED", "Registration " + registrationId + " was rejected: there is nothing to cancel");
    }
}
