package com.kmg.eventplatform.user.api;

/** A query parameter that converts to its type but breaks a rule of the API (size, length...). */
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
