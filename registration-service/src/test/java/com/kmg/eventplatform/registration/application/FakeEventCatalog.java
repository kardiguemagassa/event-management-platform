package com.kmg.eventplatform.registration.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory event-service: holds seats per reservation id, with injectable failures.
 */
class FakeEventCatalog implements EventCatalog {

    final Set<UUID> events = new HashSet<>();
    /** Organizer of each event; an event missing here was created before ownership. */
    final Map<UUID, UUID> organizers = new HashMap<>();
    final Map<UUID, UUID> heldSeats = new HashMap<>();
    RuntimeException reserveRejection;
    boolean reserveTimesOutAfterCommit;
    RuntimeException releaseFailure;
    int releaseCalls;

    @Override
    public boolean exists(UUID eventId) {
        return events.contains(eventId);
    }

    @Override
    public Optional<UUID> organizerOf(UUID eventId) {
        if (!events.contains(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        return Optional.ofNullable(organizers.get(eventId));
    }

    @Override
    public void reserveSeat(UUID eventId, UUID reservationId) {
        if (reserveRejection != null) {
            throw reserveRejection;
        }
        heldSeats.put(reservationId, eventId);
        if (reserveTimesOutAfterCommit) {
            throw new DownstreamUnavailableException("event-service", "read timed out");
        }
    }

    @Override
    public void releaseSeat(UUID eventId, UUID reservationId) {
        releaseCalls++;
        if (releaseFailure != null) {
            throw releaseFailure;
        }
        heldSeats.remove(reservationId);
    }
}
