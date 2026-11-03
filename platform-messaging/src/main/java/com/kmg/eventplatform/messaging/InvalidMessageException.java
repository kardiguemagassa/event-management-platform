package com.kmg.eventplatform.messaging;

/**
 * A record that is not an envelope: retrying cannot fix it, it goes to the dead letter topic at once.
 */
public class InvalidMessageException extends RuntimeException {

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
