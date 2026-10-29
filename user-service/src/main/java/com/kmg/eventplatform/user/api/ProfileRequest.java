package com.kmg.eventplatform.user.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The e-mail is not part of it: it comes from the access token (Keycloak is its source of truth). */
public record ProfileRequest(@NotBlank @Size(max = 100) String fullName) {
}
