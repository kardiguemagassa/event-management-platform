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

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestAttendeeDirectoryTest {

    private static final String BASE_URL = "http://user-service";

    private final UUID ada = UUID.randomUUID();
    private final String namesUrl = BASE_URL + "/api/users/names?ids=" + ada;

    private MockRestServiceServer server;
    private RestAttendeeDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new RestAttendeeDirectory(builder.build());
    }

    @AfterEach
    void verifyAllRequestsWereSent() {
        server.verify();
    }

    @Test
    void namesAreReadInOneCall() {
        server.expect(requestTo(namesUrl)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"" + ada + "\",\"fullName\":\"Ada Lovelace\",\"extra\":1}]",
                        MediaType.APPLICATION_JSON));

        assertThat(directory.namesOf(Set.of(ada))).containsExactlyEntriesOf(Map.of(ada, "Ada Lovelace"));
    }

    @Test
    void usersWithoutProfileAreAbsent() {
        server.expect(requestTo(namesUrl)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(directory.namesOf(Set.of(ada))).isEmpty();
    }

    @Test
    void emptyBodyMeansNoName() {
        server.expect(requestTo(namesUrl)).andRespond(withStatus(HttpStatus.OK));

        assertThat(directory.namesOf(Set.of(ada))).isEmpty();
    }

    @Test
    void noAttendeeNoCall() {
        assertThat(directory.namesOf(Set.of())).isEmpty();
    }

    @Test
    void serverErrorIsAnOutage() {
        server.expect(requestTo(namesUrl)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> directory.namesOf(Set.of(ada))).isInstanceOf(DownstreamUnavailableException.class);
    }

    @Test
    void refusedCallBreaksTheContract() {
        server.expect(requestTo(namesUrl)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> directory.namesOf(Set.of(ada))).isInstanceOf(DownstreamContractException.class);
    }
}
