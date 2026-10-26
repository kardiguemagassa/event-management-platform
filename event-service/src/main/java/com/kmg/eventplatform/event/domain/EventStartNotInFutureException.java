package com.kmg.eventplatform.event.domain;

import java.time.Instant;
import java.util.UUID;

public class EventStartNotInFutureException extends EventRuleException {

    public EventStartNotInFutureException(UUID eventId, Instant startAt) {
        super(eventId, "Event start " + startAt + " must be in the future");
    }

    @Override
    public String code() {
        return "EVENT_START_NOT_IN_FUTURE";
    }
}
