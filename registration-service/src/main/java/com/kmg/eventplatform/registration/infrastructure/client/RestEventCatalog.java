package com.kmg.eventplatform.registration.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kmg.eventplatform.registration.application.EventCatalog;
import com.kmg.eventplatform.registration.application.EventNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

class RestEventCatalog implements EventCatalog {

    static final String SERVICE = "event-service";

    private final RestClient client;

    RestEventCatalog(RestClient client) {
        this.client = client;
    }

    @Override
    public Optional<UUID> organizerOf(UUID eventId) {
        EventView event = DownstreamResponses.call(SERVICE, () -> client.get()
                .uri("/api/events/{id}", eventId)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return response.bodyTo(EventView.class);
                    }
                    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
                        throw new EventNotFoundException(eventId);
                    }
                    throw DownstreamResponses.unexpected(SERVICE, status);
                }));
        return Optional.ofNullable(event).map(EventView::organizerId);
    }

    /** The only field of an event that registration-service needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventView(UUID organizerId) {
    }
}
