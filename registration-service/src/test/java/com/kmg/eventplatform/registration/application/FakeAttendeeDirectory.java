package com.kmg.eventplatform.registration.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** In-memory user-service: display names per user id, with an injectable failure. */
class FakeAttendeeDirectory implements AttendeeDirectory {

    final Map<UUID, String> names = new HashMap<>();
    RuntimeException failure;
    int calls;

    @Override
    public Map<UUID, String> namesOf(Set<UUID> userIds) {
        calls++;
        if (failure != null) {
            throw failure;
        }
        return userIds.stream().filter(names::containsKey).collect(Collectors.toMap(id -> id, names::get));
    }
}
