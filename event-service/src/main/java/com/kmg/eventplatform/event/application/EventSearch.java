package com.kmg.eventplatform.event.application;

import com.kmg.eventplatform.event.domain.EventStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog filters, all optional and combined with AND.
 *
 * @param status   only events in this status
 * @param text     title or location containing this text (case-insensitive)
 * @param from     only events starting at or after this instant
 * @param mine     only the caller's own events, drafts and cancelled ones included
 * @param ids      only these events (batch read); visibility rules still apply
 */
public record EventSearch(EventStatus status, String text, Instant from, boolean mine, Set<UUID> ids) {

    public EventSearch {
        text = text == null || text.isBlank() ? null : text.strip();
        ids = ids == null || ids.isEmpty() ? null : Set.copyOf(ids);
    }

    /** No filter: the whole catalog visible to the caller. */
    public static EventSearch all() {
        return new EventSearch(null, null, null, false, null);
    }
}
