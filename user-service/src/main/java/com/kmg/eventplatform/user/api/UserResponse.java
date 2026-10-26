package com.kmg.eventplatform.user.api;

import com.kmg.eventplatform.user.domain.User;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String fullName, String email, Instant createdAt) {

    static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getCreatedAt());
    }
}
