package com.kmg.eventplatform.event.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Descriptive, user-editable part of an event. Always valid once constructed:
 * text is trimmed, a blank description becomes null, capacity is positive.
 */
public record EventDetails(String title, String description, String location, Instant startAt, int capacity) {

    public EventDetails {
        title = requireText(title, "title");
        location = requireText(location, "location");
        description = description == null || description.isBlank() ? null : description.strip();
        Objects.requireNonNull(startAt, "startAt must not be null");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
