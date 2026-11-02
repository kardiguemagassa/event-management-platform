package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.application.EventRegistration;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A registration in the list of its event's organizer.
 *
 * @param attendeeName display name of the attendee, {@code null} when unknown (no profile, or user-service
 *                     unavailable)
 */
public record EventRegistrationResponse(
        UUID id,
        UUID eventId,
        UUID userId,
        String attendeeName,
        RegistrationStatus status,
        Instant createdAt,
        Instant cancelledAt) {

    static EventRegistrationResponse from(EventRegistration eventRegistration) {
        Registration registration = eventRegistration.registration();
        return new EventRegistrationResponse(
                registration.getId(),
                registration.getEventId(),
                registration.getUserId(),
                eventRegistration.attendeeName(),
                registration.getStatus(),
                registration.getCreatedAt(),
                registration.getCancelledAt());
    }
}
