package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.AttendeeDirectory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Decorator adding retry and circuit breaking (its own breaker: user-service) to an {@link AttendeeDirectory}. */
final class ResilientAttendeeDirectory implements AttendeeDirectory {

    private final AttendeeDirectory delegate;
    private final DownstreamResilience resilience;

    ResilientAttendeeDirectory(AttendeeDirectory delegate, DownstreamResilience resilience) {
        this.delegate = delegate;
        this.resilience = resilience;
    }

    @Override
    public Map<UUID, String> namesOf(Set<UUID> userIds) {
        return resilience.call(RestAttendeeDirectory.SERVICE, () -> delegate.namesOf(userIds));
    }
}
