package com.kmg.eventplatform.registration.api;

public class InvalidSortPropertyException extends RuntimeException {

    public InvalidSortPropertyException(String property) {
        super("Sorting by '" + property + "' is not supported");
    }
}
