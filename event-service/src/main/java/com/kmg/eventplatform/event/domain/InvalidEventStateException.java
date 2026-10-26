package com.kmg.eventplatform.event.domain;

import java.util.UUID;

public final class InvalidEventStateException extends EventRuleException {

    public InvalidEventStateException(UUID eventId, EventStatus status, String action) {
        super(eventId, "Cannot " + action + " event " + eventId + " in status " + status);
    }

    @Override
    public String code() {
        return "INVALID_EVENT_STATE";
    }
}
