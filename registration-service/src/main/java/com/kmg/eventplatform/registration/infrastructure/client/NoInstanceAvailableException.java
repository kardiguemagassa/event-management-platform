package com.kmg.eventplatform.registration.infrastructure.client;

/**
 * Spring Cloud LoadBalancer found no instance of the downstream service, so no request was sent.
 * Retried like any transient failure, but ignored by the circuit breaker: the downstream is not called, so there
 * is nothing to protect, and right after a restart it would fail the half-open probes while discovery catches up.
 */
final class NoInstanceAvailableException extends TransientDownstreamException {

    NoInstanceAvailableException(Throwable cause) {
        super(cause);
    }

    /** True when the failure, or one of its causes, is a missing instance. */
    static boolean isCauseOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoInstanceAvailableException) {
                return true;
            }
        }
        return false;
    }
}
