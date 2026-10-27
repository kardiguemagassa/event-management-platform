package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestUserDirectoryTest {

    private static final String BASE_URL = "http://user-service";

    private final UUID userId = UUID.randomUUID();
    private final String userUrl = BASE_URL + "/api/users/" + userId;

    private MockRestServiceServer server;
    private RestUserDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new RestUserDirectory(builder.build());
    }

    @AfterEach
    void verifyAllRequestsWereSent() {
        server.verify();
    }

    @Test
    void existingUserIsFound() {
        server.expect(requestTo(userUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(directory.exists(userId)).isTrue();
    }

    @Test
    void unknownUserIsNotFound() {
        server.expect(requestTo(userUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(directory.exists(userId)).isFalse();
    }

    @Test
    void serverErrorMeansUnavailable() {
        server.expect(requestTo(userUrl)).andRespond(withServerError());

        assertThatThrownBy(() -> directory.exists(userId)).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void ioFailureMeansUnavailable() {
        server.expect(requestTo(userUrl)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> directory.exists(userId)).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void noRegisteredInstanceMeansUnavailable() {
        server.expect(requestTo(userUrl)).andRespond(request -> {
            throw new IllegalStateException("No instances available for user-service");
        });

        assertThatThrownBy(() -> directory.exists(userId)).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void unexpectedClientErrorIsAContractViolation() {
        server.expect(requestTo(userUrl)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> directory.exists(userId)).isInstanceOf(DownstreamContractException.class);
    }
}
