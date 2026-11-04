package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.EventCatalog;

import java.util.Optional;
import java.util.UUID;

/**
 * Decorator adding retry and circuit breaking to an existing {@link EventCatalog}.
 * Reading an event is safe to retry.
 */
final class ResilientEventCatalog implements EventCatalog {

    private final EventCatalog delegate;
    private final DownstreamResilience resilience;

    ResilientEventCatalog(EventCatalog delegate, DownstreamResilience resilience) {
        this.delegate = delegate;
        this.resilience = resilience;
    }

    @Override
    public Optional<UUID> organizerOf(UUID eventId) {
        return resilience.call(RestEventCatalog.SERVICE, () -> delegate.organizerOf(eventId));
    }

}
