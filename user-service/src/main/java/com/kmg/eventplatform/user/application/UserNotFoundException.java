package com.kmg.eventplatform.user.application;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    private final UUID userId;

    public UserNotFoundException(UUID userId) {
        super("User " + userId + " not found");
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}
