package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;

import java.time.Instant;
import java.util.UUID;

public record RegistrationResponse(
        UUID id,
        UUID eventId,
        UUID userId,
        RegistrationStatus status,
        Instant createdAt,
        Instant cancelledAt) {

    static RegistrationResponse from(Registration registration) {
        return new RegistrationResponse(
                registration.getId(),
                registration.getEventId(),
                registration.getUserId(),
                registration.getStatus(),
                registration.getCreatedAt(),
                registration.getCancelledAt());
    }
}
