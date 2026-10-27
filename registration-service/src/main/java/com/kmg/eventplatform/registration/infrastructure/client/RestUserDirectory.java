package com.kmg.eventplatform.registration.infrastructure.client;

import com.kmg.eventplatform.registration.application.UserDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.UUID;

class RestUserDirectory implements UserDirectory {

    private static final String SERVICE = "user-service";

    private final RestClient client;

    RestUserDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public boolean exists(UUID userId) {
        Boolean found = DownstreamResponses.call(SERVICE, () -> client.get()
                .uri("/api/users/{id}", userId)
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
}
