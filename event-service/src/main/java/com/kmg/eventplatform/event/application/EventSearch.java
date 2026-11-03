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
 * @param scope    which events are searched at all
 * @param ids      only these events (batch read); the scope still applies
 */
public record EventSearch(EventStatus status, String text, Instant from, Scope scope, Set<UUID> ids) {

    /** Which events a search covers, before any filter. */
    public enum Scope {
        /** The catalog as the caller sees it: published and cancelled events, plus the caller's own drafts. */
        VISIBLE,
        /** Every event of the caller, drafts and cancelled ones included (organizers). */
        MINE,
        /** Every event of every organizer, drafts included (administrators). */
        ALL
    }

    public EventSearch {
        text = text == null || text.isBlank() ? null : text.strip();
        ids = ids == null || ids.isEmpty() ? null : Set.copyOf(ids);
        scope = scope == null ? Scope.VISIBLE : scope;
    }

    /** No filter: the whole catalog visible to the caller. */
    public static EventSearch all() {
        return new EventSearch(null, null, null, Scope.VISIBLE, null);
    }
}
