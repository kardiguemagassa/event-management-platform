package com.kmg.eventplatform.event.application;

/** Result of a reserve/release call; replays are reported explicitly but are not errors. */
public enum CapacityOutcome {
    RESERVED,
    ALREADY_RESERVED,
    RELEASED,
    NOT_RESERVED
}
