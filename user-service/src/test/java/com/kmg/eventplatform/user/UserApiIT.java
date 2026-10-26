package com.kmg.eventplatform.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class UserApiIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    @Value("${local.server.port}")
    private int port;

    @Test
    void fullLifecycle() {
        String email = uniqueEmail();

        ResponseEntity<Map<String, Object>> created = create("Ada Lovelace", email.toUpperCase());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("email", email);
        String location = created.getHeaders().getLocation().toString();
        String id = (String) created.getBody().get("id");
        assertThat(location).endsWith("/api/users/" + id);

        ResponseEntity<Map<String, Object>> fetched = client().get().uri(location)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).containsEntry("fullName", "Ada Lovelace");

        String newEmail = uniqueEmail();
        ResponseEntity<Map<String, Object>> updated = client().put().uri("/api/users/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fullName", "Ada King", "email", newEmail))
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).containsEntry("email", newEmail);

        ResponseEntity<Map<String, Object>> page = client().get().uri("/api/users?size=100")
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) page.getBody().get("content")).isNotEmpty();

        ResponseEntity<Void> deleted = client().delete().uri("/api/users/{id}", id)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toBodilessEntity();
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> gone = client().get().uri("/api/users/{id}", id)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(gone.getBody()).containsEntry("code", "USER_NOT_FOUND");
    }

    @Test
    void duplicateEmailIsCaseInsensitive() {
        String email = uniqueEmail();
        assertThat(create("Ada Lovelace", email.toUpperCase()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map<String, Object>> duplicate = create("Ada Bis", email);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("code", "EMAIL_ALREADY_USED");
    }

    @Test
    void concurrentRegistrationsYieldExactlyOneCreated() throws Exception {
        String email = uniqueEmail();
        int attempts = 10;
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    startSignal.await();
                    return create("Racer", email).getStatusCode().value();
                }));
            }
            startSignal.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        }
    }

    private ResponseEntity<Map<String, Object>> create(String fullName, String email) {
        return client().post().uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fullName", fullName, "email", email))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(JSON_OBJECT);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
