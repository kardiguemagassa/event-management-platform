package com.kmg.eventplatform.registration.application;

/**
 * Failures of the registration use cases that do not come from the registration aggregate itself.
 * Sealed: the API layer maps every subtype with an exhaustive switch.
 */
public sealed abstract class RegistrationFailureException extends RuntimeException
        permits EventNotFoundException, EventFullException, EventNotRegistrableException,
        DownstreamUnavailableException, UserNotFoundException, RegistrationNotFoundException,
        DuplicateRegistrationException {

    private final String code;

    protected RegistrationFailureException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected RegistrationFailureException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
