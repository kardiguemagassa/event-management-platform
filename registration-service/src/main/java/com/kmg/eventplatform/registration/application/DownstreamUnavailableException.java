package com.kmg.eventplatform.registration.application;

public final class DownstreamUnavailableException extends RegistrationFailureException {

    private static final String CODE = "DOWNSTREAM_UNAVAILABLE";

    public DownstreamUnavailableException(String service, Throwable cause) {
        super(CODE, service + " is unavailable", cause);
    }

    public DownstreamUnavailableException(String service, String detail) {
        super(CODE, service + " is unavailable: " + detail);
    }
}
