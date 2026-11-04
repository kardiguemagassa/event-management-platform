package com.kmg.eventplatform.registration.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Port to the service owning the events: who organizes one, to decide who may read its registrations. Seats are not
 * asked here: they are requested asynchronously ({@link SeatRequests}).
 */
public interface EventCatalog {

    /**
     * @return the Keycloak id of the event's organizer, empty for an event created before ownership
     * @throws EventNotFoundException         if the event does not exist
     * @throws DownstreamUnavailableException if the answer cannot be obtained
     */
    Optional<UUID> organizerOf(UUID eventId);
}
