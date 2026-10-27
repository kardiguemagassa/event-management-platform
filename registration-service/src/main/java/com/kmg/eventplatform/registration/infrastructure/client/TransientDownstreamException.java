package com.kmg.eventplatform.registration.infrastructure.client;

/**
 * Marker cause of a {@link com.kmg.eventplatform.registration.application.DownstreamUnavailableException}
 * for failures that are fast and likely to succeed on a second attempt: connection refused, no registered
 * instance, 502/503/504. Timeouts and 500 are deliberately not transient: retrying them adds load and latency.
 */
class TransientDownstreamException extends RuntimeException {

    TransientDownstreamException(String message) {
        super(message);
    }

    TransientDownstreamException(Throwable cause) {
        super(cause);
    }
}
