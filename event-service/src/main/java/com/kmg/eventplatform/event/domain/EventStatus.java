package com.kmg.eventplatform.event.domain;

/** Lifecycle: DRAFT -> PUBLISHED -> CANCELLED, and DRAFT -> CANCELLED. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    CANCELLED
}
