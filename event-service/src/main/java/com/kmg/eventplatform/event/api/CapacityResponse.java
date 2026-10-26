package com.kmg.eventplatform.event.api;

import com.kmg.eventplatform.event.application.CapacityOutcome;
import java.util.UUID;

public record CapacityResponse(UUID eventId, UUID reservationId, CapacityOutcome outcome) {
}
