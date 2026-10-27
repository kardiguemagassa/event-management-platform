package com.kmg.eventplatform.registration.domain;

/**
 * Business rule violations of the registration domain.
 * Sealed: the API layer maps every subtype with an exhaustive switch.
 */
public sealed abstract class RegistrationRuleException extends RuntimeException
        permits RegistrationAlreadyCancelledException {

    private final String code;

    protected RegistrationRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
