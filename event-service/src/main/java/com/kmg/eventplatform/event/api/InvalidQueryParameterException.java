package com.kmg.eventplatform.event.api;

/** A query parameter that converts to its type but breaks a rule of the API (length, size, allowed value). */
class InvalidQueryParameterException extends RuntimeException {

    private final String parameter;

    InvalidQueryParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    String getParameter() {
        return parameter;
    }
}
