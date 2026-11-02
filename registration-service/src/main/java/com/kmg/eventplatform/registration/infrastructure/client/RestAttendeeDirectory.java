package com.kmg.eventplatform.registration.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kmg.eventplatform.registration.application.AttendeeDirectory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** One call per page of attendees: {@code GET /api/users/names?ids=...} (internal route of user-service). */
class RestAttendeeDirectory implements AttendeeDirectory {

    static final String SERVICE = "user-service";

    private static final ParameterizedTypeReference<List<UserName>> USER_NAMES = new ParameterizedTypeReference<>() {
    };

    private final RestClient client;

    RestAttendeeDirectory(RestClient client) {
        this.client = client;
    }

    @Override
    public Map<UUID, String> namesOf(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        String ids = userIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        List<UserName> names = DownstreamResponses.call(SERVICE, () -> client.get()
                .uri(uri -> uri.path("/api/users/names").queryParam("ids", ids).build())
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status.is2xxSuccessful()) {
                        return response.bodyTo(USER_NAMES);
                    }
                    throw DownstreamResponses.unexpected(SERVICE, status);
                }));
        return names == null ? Map.of()
                : names.stream().collect(Collectors.toMap(UserName::id, UserName::fullName));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserName(UUID id, String fullName) {
    }
}
