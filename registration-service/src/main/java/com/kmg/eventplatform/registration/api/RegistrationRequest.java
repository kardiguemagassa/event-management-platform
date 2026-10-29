package com.kmg.eventplatform.registration.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** The attendee is the caller (access token): no user id is accepted from the request. */
public record RegistrationRequest(
        @NotNull UUID eventId) {
}
