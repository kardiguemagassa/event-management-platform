package com.kmg.eventplatform.event.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * @param reservationId idempotency key chosen by the caller (registration-service uses the
 *                      registration id): replaying the same request has no additional effect
 */
public record CapacityRequest(@NotNull UUID reservationId) {
}
