package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * @param status          PENDING until event-service answers, then CONFIRMED or REJECTED; CANCELLED by the attendee
 * @param rejectionReason why the seat was refused, for a REJECTED registration: {@code EVENT_FULL},
 *                        {@code EVENT_NOT_REGISTRABLE} or {@code EVENT_NOT_FOUND}
 */
public record RegistrationResponse(
        UUID id,
        UUID eventId,
        UUID userId,
        RegistrationStatus status,
        String rejectionReason,
        Instant createdAt,
        Instant cancelledAt) {

    static RegistrationResponse from(Registration registration) {
        return new RegistrationResponse(
                registration.getId(),
                registration.getEventId(),
                registration.getUserId(),
                registration.getStatus(),
                registration.getRejectionReason(),
                registration.getCreatedAt(),
                registration.getCancelledAt());
    }
}
