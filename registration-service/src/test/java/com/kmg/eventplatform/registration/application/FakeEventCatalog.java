package com.kmg.eventplatform.registration.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory event-service: the events and their organizers.
 */
class FakeEventCatalog implements EventCatalog {

    final Set<UUID> events = new HashSet<>();
    /** Organizer of each event; an event missing here was created before ownership. */
    final Map<UUID, UUID> organizers = new HashMap<>();

    @Override
    public Optional<UUID> organizerOf(UUID eventId) {
        if (!events.contains(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        return Optional.ofNullable(organizers.get(eventId));
    }
}
