package com.kmg.eventplatform.event.application;

/** Result of a seat release; releasing a seat that is not held is a no-op, reported explicitly. */
public enum CapacityOutcome {
    RELEASED,
    NOT_RESERVED
}
