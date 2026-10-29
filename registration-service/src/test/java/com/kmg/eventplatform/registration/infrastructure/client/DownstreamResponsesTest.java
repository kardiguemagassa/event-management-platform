package com.kmg.eventplatform.registration.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;

class DownstreamResponsesTest {

    @Test
    void missingAccessTokenMakesTheServiceUnavailableWithoutRetry() {
        ClientAuthorizationException noToken =
                new ClientAuthorizationException(new OAuth2Error("invalid_client"), "event-platform");

        assertThatThrownBy(() -> DownstreamResponses.call("event-service", () -> {
            throw noToken;
        }))
                .isInstanceOf(DownstreamUnavailableException.class)
                .hasCause(noToken);
    }
}
