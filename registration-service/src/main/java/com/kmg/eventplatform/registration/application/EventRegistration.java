package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.Registration;

/**
 * A registration as its event's organizer sees it.
 *
 * @param attendeeName display name of the attendee, {@code null} when unknown (no profile, or user-service
 *                     unavailable: the list is still served)
 */
public record EventRegistration(Registration registration, String attendeeName) {
}
