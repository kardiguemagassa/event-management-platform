package com.kmg.eventplatform.registration.application;

import java.util.UUID;

/**
 * Port to the seats of event-service, asynchronous: a request is written with the registration change, in the same
 * transaction, and answered later ({@link RegistrationService#seatReserved}, {@link RegistrationService#seatRejected}).
 * Both requests are idempotent on event-service's side, per reservation id.
 */
public interface SeatRequests {

    /**
     * @param reservationId the registration id
     */
    void reserve(UUID eventId, UUID reservationId);

    /**
     * Releasing a seat that is not held (refused, or not reserved yet and refused later) is a no-op.
     */
    void release(UUID eventId, UUID reservationId);
}
