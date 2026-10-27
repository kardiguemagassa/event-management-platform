package com.kmg.eventplatform.registration.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RegistrationRequest(
        @NotNull UUID eventId,
        @NotNull UUID userId) {
}
