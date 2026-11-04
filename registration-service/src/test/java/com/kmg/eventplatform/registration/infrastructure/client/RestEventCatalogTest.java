package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestEventCatalogTest {

    private static final String BASE_URL = "http://event-service";

    private final UUID eventId = UUID.randomUUID();
    private final String eventUrl = BASE_URL + "/api/events/" + eventId;

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
    void organizerIsReadFromTheEvent() {
        UUID organizer = UUID.randomUUID();
        server.expect(requestTo(eventUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"" + eventId + "\",\"title\":\"Meetup\",\"organizerId\":\""
                        + organizer + "\"}", MediaType.APPLICATION_JSON));

        assertThat(catalog.organizerOf(eventId)).hasValue(organizer);
    }

    @Test
    void eventCreatedBeforeOwnershipHasNoOrganizer() {
        server.expect(requestTo(eventUrl))
                .andRespond(withSuccess("{\"id\":\"" + eventId + "\",\"organizerId\":null}", MediaType.APPLICATION_JSON));

        assertThat(catalog.organizerOf(eventId)).isEmpty();
    }

    @Test
    void organizerOfUnknownEventIsNotFound() {
        server.expect(requestTo(eventUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> catalog.organizerOf(eventId)).isInstanceOf(EventNotFoundException.class);
    }

    @Test
    void organizerLookupOnAFailingEventServiceIsUnavailable() {
        server.expect(requestTo(eventUrl)).andRespond(withServerError());

        assertThatThrownBy(() -> catalog.organizerOf(eventId)).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void lookupWithoutRegisteredInstanceIsUnavailable() {
        server.expect(requestTo(eventUrl)).andRespond(request -> {
            throw new IllegalStateException("No instances available for event-service");
        });

        assertThatThrownBy(() -> catalog.organizerOf(eventId))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasCauseInstanceOf(NoInstanceAvailableException.class);
    }

    @Test
    void anUnexpectedAnswerIsAContractViolation() {
        server.expect(requestTo(eventUrl)).andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> catalog.organizerOf(eventId)).isInstanceOf(DownstreamContractException.class);
    }

    @Test
    void onlyFastFailuresAreMarkedTransient() {
        server.expect(requestTo(eventUrl)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(eventUrl)).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(eventUrl)).andRespond(withServerError());
        server.expect(requestTo(eventUrl)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(catchThrowable(() -> catalog.organizerOf(eventId)))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasCauseInstanceOf(TransientDownstreamException.class);
        assertThat(catchThrowable(() -> catalog.organizerOf(eventId)))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasCauseInstanceOf(TransientDownstreamException.class);
        assertThat(catchThrowable(() -> catalog.organizerOf(eventId)))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasNoCause();
        assertThat(catchThrowable(() -> catalog.organizerOf(eventId)).getCause())
                .isNotInstanceOf(TransientDownstreamException.class);
    }
}
