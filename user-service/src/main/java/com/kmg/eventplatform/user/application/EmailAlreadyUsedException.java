package com.kmg.eventplatform.user.application;

public class EmailAlreadyUsedException extends RuntimeException {

    private final String email;

    public EmailAlreadyUsedException(String email) {
        super("Email " + email + " is already used");
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}
