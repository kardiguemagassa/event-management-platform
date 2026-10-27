package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.EventCatalog;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.registration.application.EventNotFoundException;
import com.kmg.eventplatform.registration.application.EventNotRegistrableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.UUID;

class RestEventCatalog implements EventCatalog {

    private static final String SERVICE = "event-service";

    private final RestClient client;

    RestEventCatalog(RestClient client) {
        this.client = client;
    }

    @Override
    public boolean exists(UUID eventId) {
        Boolean found = DownstreamResponses.call(SERVICE, () -> client.get()
                .uri("/api/events/{id}", eventId)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return true;
                    }
                    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        return false;
                    }
                    throw DownstreamResponses.unexpected(SERVICE, status);
                }));
        return Boolean.TRUE.equals(found);
    }

    @Override
    public void reserveSeat(UUID eventId, UUID reservationId) {
        postCapacity("/api/events/{id}/capacity/reserve", eventId, reservationId);
    }

    @Override
    public void releaseSeat(UUID eventId, UUID reservationId) {
        postCapacity("/api/events/{id}/capacity/release", eventId, reservationId);
    }

    private void postCapacity(String path, UUID eventId, UUID reservationId) {
        DownstreamResponses.call(SERVICE, () -> client.post()
                .uri(path, eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CapacityRequest(reservationId))
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return null;
                    }
                    if (status.is4xxClientError()) {
                        throw rejection(eventId, status, DownstreamResponses.problemCode(response));
                    }
                    throw DownstreamResponses.unexpected(SERVICE, status);
                }));
    }

    /**
     * Business rejections are recognised by their stable ProblemDetail code, not by the HTTP status alone.
     */
    private static RuntimeException rejection(UUID eventId, HttpStatusCode status, String code) {
        return switch (code) {
            case "EVENT_NOT_FOUND" -> new EventNotFoundException(eventId);
            case "EVENT_FULL" -> new EventFullException(eventId);
            case "EVENT_NOT_REGISTRABLE" -> new EventNotRegistrableException(eventId);
            default -> DownstreamResponses.unexpected(SERVICE, status);
        };
    }

    record CapacityRequest(UUID reservationId) {
    }
}
