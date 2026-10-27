package com.kmg.eventplatform.registration.application;

import java.util.UUID;

public final class UserNotFoundException extends RegistrationFailureException {

    public UserNotFoundException(UUID userId) {
        super("USER_NOT_FOUND", "User " + userId + " not found");
    }
}
