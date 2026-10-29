package com.kmg.eventplatform.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.kmg.eventplatform.security.test.TestTokens;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * End-to-end: real HTTP server, real PostgreSQL (Testcontainers), real token validation, all layers wired together.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@Import(TestcontainersConfiguration.class)
class EventApiIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    private static final UUID ORGANIZER_ID = UUID.randomUUID();
    private static final String ORGANIZER = TestTokens.forUser(ORGANIZER_ID, "ORGANIZER", "ATTENDEE");
    private static final String OTHER_ORGANIZER = TestTokens.forUser(UUID.randomUUID(), "ORGANIZER");
    private static final String ATTENDEE = TestTokens.forUser(UUID.randomUUID(), "ATTENDEE");
    private static final String ANONYMOUS = null;

    @DynamicPropertySource
    static void tokenValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
    }

    @Value("${local.server.port}")
    private int port;

    private static Map<String, Object> eventJson(String title, int capacity) {
        return Map.of("title", title, "description", "Talks", "location", "Paris",
                "startAt", "2099-06-01T18:00:00Z", "capacity", capacity);
    }

    private String draft() {
        return (String) send("POST", "/api/events", eventJson("Draft", 10), ORGANIZER).getBody().get("id");
    }

    @Test
    void fullLifecycle() {
        ResponseEntity<Map<String, Object>> created =
                send("POST", "/api/events", eventJson("Spring Meetup", 50), ORGANIZER);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "DRAFT").containsEntry("availableSeats", 50)
                .containsEntry("organizerId", ORGANIZER_ID.toString());
        String id = (String) created.getBody().get("id");
        assertThat(created.getHeaders().getLocation().toString()).endsWith("/api/events/" + id);

        assertThat(send("POST", "/api/events/" + id + "/publish", null, ORGANIZER).getBody())
                .containsEntry("status", "PUBLISHED");
        assertThat(send("POST", "/api/events/" + id + "/publish", null, ORGANIZER).getStatusCode())
                .as("publish is idempotent").isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> updated =
                send("PUT", "/api/events/" + id, eventJson("Java Day", 80), ORGANIZER);
        assertThat(updated.getBody()).containsEntry("title", "Java Day").containsEntry("capacity", 80);

        ResponseEntity<Map<String, Object>> published =
                send("GET", "/api/events?status=PUBLISHED&size=100", null, ANONYMOUS);
        assertThat((List<?>) published.getBody().get("content")).isNotEmpty();

        assertThat(send("POST", "/api/events/" + id + "/cancel", null, ORGANIZER).getBody())
                .containsEntry("status", "CANCELLED");

        ResponseEntity<Map<String, Object>> republish = send("POST", "/api/events/" + id + "/publish", null, ORGANIZER);
        assertThat(republish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(republish.getBody()).containsEntry("code", "INVALID_EVENT_STATE");

        assertThat(send("DELETE", "/api/events/" + id, null, ORGANIZER).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map<String, Object>> gone = send("GET", "/api/events/" + id, null, ANONYMOUS);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getBody()).containsEntry("code", "EVENT_NOT_FOUND");
    }

    @Test
    void draftIsVisibleToItsOrganizerOnly() {
        String id = draft();

        assertThat(send("GET", "/api/events/" + id, null, ORGANIZER).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send("GET", "/api/events/" + id, null, ANONYMOUS).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send("GET", "/api/events/" + id, null, OTHER_ORGANIZER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ids(send("GET", "/api/events?size=100", null, ANONYMOUS))).doesNotContain(id);
        assertThat(ids(send("GET", "/api/events?status=DRAFT&size=100", null, ORGANIZER))).contains(id);
    }

    @Test
    void anotherOrganizerCannotChangeThePublishedEvent() {
        String id = draft();
        send("POST", "/api/events/" + id + "/publish", null, ORGANIZER);

        ResponseEntity<Map<String, Object>> cancel = send("POST", "/api/events/" + id + "/cancel", null, OTHER_ORGANIZER);

        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cancel.getBody()).containsEntry("code", "NOT_EVENT_ORGANIZER");
        assertThat(send("GET", "/api/events/" + id, null, ANONYMOUS).getBody()).containsEntry("status", "PUBLISHED");
    }

    @Test
    void onlyOrganizersCreateEvents() {
        ResponseEntity<Map<String, Object>> anonymous = send("POST", "/api/events", eventJson("Meetup", 10), ANONYMOUS);
        ResponseEntity<Map<String, Object>> attendee = send("POST", "/api/events", eventJson("Meetup", 10), ATTENDEE);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody()).containsEntry("code", "UNAUTHENTICATED");
        assertThat(attendee.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attendee.getBody()).containsEntry("code", "ACCESS_DENIED");
    }

    @Test
    void rejectsEventStartingInThePast() {
        Map<String, Object> past = Map.of("title", "Old", "location", "Paris",
                "startAt", "2000-01-01T00:00:00Z", "capacity", 10);

        ResponseEntity<Map<String, Object>> response = send("POST", "/api/events", past, ORGANIZER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    @SuppressWarnings("unchecked")
    private static List<String> ids(ResponseEntity<Map<String, Object>> page) {
        return ((List<Map<String, Object>>) page.getBody().get("content")).stream()
                .map(event -> (String) event.get("id"))
                .toList();
    }

    private ResponseEntity<Map<String, Object>> send(String method, String path, Object body, String token) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port)
                .method(HttpMethod.valueOf(method))
                .uri(path);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(JSON_OBJECT);
    }
}
