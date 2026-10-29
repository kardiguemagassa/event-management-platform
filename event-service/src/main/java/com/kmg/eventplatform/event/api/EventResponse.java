package com.kmg.eventplatform.event.api;

import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventStatus;
import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String title,
        String description,
        String location,
        Instant startAt,
        int capacity,
        int reservedSeats,
        int availableSeats,
        EventStatus status,
        Instant createdAt,
        UUID organizerId) {

    static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getTitle(), event.getDescription(), event.getLocation(),
                event.getStartAt(), event.getCapacity(), event.getReservedSeats(), event.availableSeats(),
                event.getStatus(), event.getCreatedAt(), event.getOrganizerId());
    }
}
