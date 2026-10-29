package com.kmg.eventplatform.registration.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to the service owning events and their capacity.
 * Seat operations are idempotent per reservation id, so they are safe to retry.
 */
public interface EventCatalog {

    /**
     * @throws DownstreamUnavailableException if the answer cannot be obtained
     */
    boolean exists(UUID eventId);

    /**
     * @return the Keycloak id of the event's organizer, empty for an event created before ownership
     * @throws EventNotFoundException         if the event does not exist
     * @throws DownstreamUnavailableException if the answer cannot be obtained
     */
    Optional<UUID> organizerOf(UUID eventId);

    /**
     * @throws EventNotFoundException       if the event does not exist
     * @throws EventFullException           if no seat is left
     * @throws EventNotRegistrableException if the event does not accept registrations
     * @throws DownstreamUnavailableException if the outcome is unknown: the seat may or may not be held,
     *                                        so the caller must compensate with {@link #releaseSeat}
     */
    void reserveSeat(UUID eventId, UUID reservationId);

    /**
     * Releasing a seat that is not held is a no-op.
     *
     * @throws EventNotFoundException         if the event does not exist
     * @throws DownstreamUnavailableException if the outcome is unknown
     */
    void releaseSeat(UUID eventId, UUID reservationId);
}
