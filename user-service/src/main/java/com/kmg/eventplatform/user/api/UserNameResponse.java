package com.kmg.eventplatform.user.api;

import com.kmg.eventplatform.user.domain.User;
import java.util.UUID;

/** Display name only: what another service may show of a user, never the e-mail. */
public record UserNameResponse(UUID id, String fullName) {

    static UserNameResponse from(User user) {
        return new UserNameResponse(user.getId(), user.getFullName());
    }
}
