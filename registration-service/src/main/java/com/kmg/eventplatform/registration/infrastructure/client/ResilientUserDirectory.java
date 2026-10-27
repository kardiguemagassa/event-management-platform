package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.UserDirectory;

import java.util.UUID;

/** Decorator adding retry and circuit breaking to an existing {@link UserDirectory}. */
final class ResilientUserDirectory implements UserDirectory {

    private final UserDirectory delegate;
    private final DownstreamResilience resilience;

    ResilientUserDirectory(UserDirectory delegate, DownstreamResilience resilience) {
        this.delegate = delegate;
        this.resilience = resilience;
    }

    @Override
    public boolean exists(UUID userId) {
        return resilience.call(RestUserDirectory.SERVICE, () -> delegate.exists(userId));
    }
}
