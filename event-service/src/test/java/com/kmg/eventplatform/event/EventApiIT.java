package com.kmg.eventplatform.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * End-to-end: real HTTP server, real PostgreSQL (Testcontainers), all layers wired together.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@Import(TestcontainersConfiguration.class)
class EventApiIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    @Value("${local.server.port}")
    private int port;

    private static Map<String, Object> eventJson(String title, int capacity) {
        return Map.of("title", title, "description", "Talks", "location", "Paris",
                "startAt", "2099-06-01T18:00:00Z", "capacity", capacity);
    }

    @Test
    void fullLifecycle() {
        ResponseEntity<Map<String, Object>> created = send("POST", "/api/events", eventJson("Spring Meetup", 50));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "DRAFT").containsEntry("availableSeats", 50);
        String id = (String) created.getBody().get("id");
        assertThat(created.getHeaders().getLocation().toString()).endsWith("/api/events/" + id);

        assertThat(send("POST", "/api/events/" + id + "/publish", null).getBody())
                .containsEntry("status", "PUBLISHED");
        assertThat(send("POST", "/api/events/" + id + "/publish", null).getStatusCode())
                .as("publish is idempotent").isEqualTo(HttpStatus.OK);

        ResponseEntity<Map<String, Object>> updated = send("PUT", "/api/events/" + id, eventJson("Java Day", 80));
        assertThat(updated.getBody()).containsEntry("title", "Java Day").containsEntry("capacity", 80);

        ResponseEntity<Map<String, Object>> published = send("GET", "/api/events?status=PUBLISHED&size=100", null);
        assertThat((List<?>) published.getBody().get("content")).isNotEmpty();

        assertThat(send("POST", "/api/events/" + id + "/cancel", null).getBody())
                .containsEntry("status", "CANCELLED");

        ResponseEntity<Map<String, Object>> republish = send("POST", "/api/events/" + id + "/publish", null);
        assertThat(republish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(republish.getBody()).containsEntry("code", "INVALID_EVENT_STATE");

        assertThat(send("DELETE", "/api/events/" + id, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map<String, Object>> gone = send("GET", "/api/events/" + id, null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getBody()).containsEntry("code", "EVENT_NOT_FOUND");
    }

    @Test
    void rejectsEventStartingInThePast() {
        Map<String, Object> past = Map.of("title", "Old", "location", "Paris",
                "startAt", "2000-01-01T00:00:00Z", "capacity", 10);

        ResponseEntity<Map<String, Object>> response = send("POST", "/api/events", past);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).containsEntry("code", "VALIDATION_FAILED");
    }

    private ResponseEntity<Map<String, Object>> send(String method, String path, Object body) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port)
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> { })
                .toEntity(JSON_OBJECT);
    }
}
