package com.kmg.eventplatform.registration.infrastructure.client;

/**
 * A downstream answered in a way our contract does not allow: a bug on one side, not an outage.
 * Deliberately not an IllegalStateException, which is how Spring Cloud LoadBalancer reports "no instance".
 */
class DownstreamContractException extends RuntimeException {

    DownstreamContractException(String message) {
        super(message);
    }
}
