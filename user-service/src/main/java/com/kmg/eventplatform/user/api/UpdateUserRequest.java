package com.kmg.eventplatform.user.api;

import com.kmg.eventplatform.user.application.UpdateUserCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Full replacement (PUT semantics): every field is required. */
public record UpdateUserRequest(
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Email @Size(max = 254) String email) {

    UpdateUserCommand toCommand() {
        return new UpdateUserCommand(fullName, email);
    }
}
