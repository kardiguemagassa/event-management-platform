package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.EventCatalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Decorator adding retry and circuit breaking to an existing {@link EventCatalog}.
 * Retrying seat operations is safe because they are idempotent per reservation id.
 */
final class ResilientEventCatalog implements EventCatalog {

    private final EventCatalog delegate;
    private final DownstreamResilience resilience;

    ResilientEventCatalog(EventCatalog delegate, DownstreamResilience resilience) {
        this.delegate = delegate;
        this.resilience = resilience;
    }

    @Override
    public boolean exists(UUID eventId) {
        return resilience.call(RestEventCatalog.SERVICE, () -> delegate.exists(eventId));
    }

    @Override
    public Optional<UUID> organizerOf(UUID eventId) {
        return resilience.call(RestEventCatalog.SERVICE, () -> delegate.organizerOf(eventId));
    }

    @Override
    public void reserveSeat(UUID eventId, UUID reservationId) {
        resilience.call(RestEventCatalog.SERVICE, () -> {
            delegate.reserveSeat(eventId, reservationId);
            return null;
        });
    }

    @Override
    public void releaseSeat(UUID eventId, UUID reservationId) {
        resilience.call(RestEventCatalog.SERVICE, () -> {
            delegate.releaseSeat(eventId, reservationId);
            return null;
        });
    }
}
