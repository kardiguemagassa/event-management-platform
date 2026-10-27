package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.registration.application.EventNotFoundException;
import com.kmg.eventplatform.registration.application.EventNotRegistrableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestEventCatalogTest {

    private static final String BASE_URL = "http://event-service";

    private final UUID eventId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final String eventUrl = BASE_URL + "/api/events/" + eventId;
    private final String reserveUrl = eventUrl + "/capacity/reserve";
    private final String releaseUrl = eventUrl + "/capacity/release";

    private MockRestServiceServer server;
    private RestEventCatalog catalog;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        catalog = new RestEventCatalog(builder.build());
    }

    @AfterEach
    void verifyAllRequestsWereSent() {
        server.verify();
    }

    @Test
    void existingEventIsFound() {
        server.expect(requestTo(eventUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(catalog.exists(eventId)).isTrue();
    }

    @Test
    void unknownEventIsNotFound() {
        server.expect(requestTo(eventUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(catalog.exists(eventId)).isFalse();
    }

    @Test
    void reserveSendsReservationId() {
        server.expect(requestTo(reserveUrl)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andRespond(withSuccess("{\"outcome\":\"RESERVED\"}", MediaType.APPLICATION_JSON));

        assertThatNoException().isThrownBy(() -> catalog.reserveSeat(eventId, reservationId));
    }

    @Test
    void reserveMapsFullEvent() {
        server.expect(requestTo(reserveUrl)).andRespond(problem(HttpStatus.CONFLICT, "EVENT_FULL"));

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId)).isInstanceOf(EventFullException.class);
    }

    @Test
    void reserveMapsNotRegistrableEvent() {
        server.expect(requestTo(reserveUrl)).andRespond(problem(HttpStatus.CONFLICT, "EVENT_NOT_REGISTRABLE"));

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId))
                .isInstanceOf(EventNotRegistrableException.class);
    }

    @Test
    void reserveMapsUnknownEvent() {
        server.expect(requestTo(reserveUrl)).andRespond(problem(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"));

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void reserveTreatsServerErrorAsUnavailable() {
        server.expect(requestTo(reserveUrl)).andRespond(withServerError());

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void reserveTreatsTimeoutAsUnavailable() {
        server.expect(requestTo(reserveUrl)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void reserveWithoutRegisteredInstanceIsUnavailable() {
        server.expect(requestTo(reserveUrl)).andRespond(request -> {
            throw new IllegalStateException("No instances available for event-service");
        });

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId))
                .isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void reserveTreatsUnknownRejectionAsContractViolation() {
        server.expect(requestTo(reserveUrl)).andRespond(problem(HttpStatus.CONFLICT, "RESERVATION_CONFLICT"));

        assertThatThrownBy(() -> catalog.reserveSeat(eventId, reservationId))
                .isInstanceOf(DownstreamContractException.class);
    }

    @Test
    void releaseSucceedsEvenWhenNothingWasReserved() {
        server.expect(requestTo(releaseUrl)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.reservationId").value(reservationId.toString()))
                .andRespond(withSuccess("{\"outcome\":\"NOT_RESERVED\"}", MediaType.APPLICATION_JSON));

        assertThatNoException().isThrownBy(() -> catalog.releaseSeat(eventId, reservationId));
    }

    private static ResponseCreator problem(HttpStatus status, String code) {
        return withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":" + status.value() + ",\"code\":\"" + code + "\"}");
    }
}
