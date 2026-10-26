package com.kmg.eventplatform.user.api;

import com.kmg.eventplatform.user.application.RegisterUserCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Size(max = 100) String fullName,
        @NotBlank @Email @Size(max = 254) String email) {

    RegisterUserCommand toCommand() {
        return new RegisterUserCommand(fullName, email);
    }
}
