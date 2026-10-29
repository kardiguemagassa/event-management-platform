package com.kmg.eventplatform.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.kmg.eventplatform.security.test.TestTokens;
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
class UserApiIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    @DynamicPropertySource
    static void tokenValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
    }

    @Value("${local.server.port}")
    private int port;

    @Test
    void profileLifecycle() {
        String email = uniqueEmail();
        String ada = TestTokens.withEmail(UUID.randomUUID(), email.toUpperCase(), "ATTENDEE");

        assertThat(send(HttpMethod.GET, ada, null).getStatusCode()).as("no profile yet").isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map<String, Object>> created = send(HttpMethod.PUT, ada, Map.of("fullName", "Ada Lovelace"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation().toString()).endsWith("/api/users/me");
        assertThat(created.getBody()).containsEntry("email", email).containsEntry("fullName", "Ada Lovelace");

        ResponseEntity<Map<String, Object>> updated = send(HttpMethod.PUT, ada, Map.of("fullName", "Ada King"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(send(HttpMethod.GET, ada, null).getBody()).containsEntry("fullName", "Ada King");

        assertThat(send(HttpMethod.DELETE, ada, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Map<String, Object>> gone = send(HttpMethod.GET, ada, null);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(gone.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(gone.getBody()).containsEntry("code", "USER_NOT_FOUND");
    }

    @Test
    void emailOfAnotherProfileIsAConflict() {
        String email = uniqueEmail();
        send(HttpMethod.PUT, TestTokens.withEmail(UUID.randomUUID(), email), Map.of("fullName", "Ada Lovelace"));

        ResponseEntity<Map<String, Object>> duplicate = send(HttpMethod.PUT,
                TestTokens.withEmail(UUID.randomUUID(), email.toUpperCase()), Map.of("fullName", "Ada Bis"));

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).containsEntry("code", "EMAIL_ALREADY_USED");
    }

    @Test
    void concurrentFirstSavesCreateTheProfileOnce() throws Exception {
        String token = TestTokens.withEmail(UUID.randomUUID(), uniqueEmail());
        int attempts = 10;
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    startSignal.await();
                    return send(HttpMethod.PUT, token, Map.of("fullName", "Racer")).getStatusCode().value();
                }));
            }
            startSignal.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).isSubsetOf(201, 200, 409);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        }
    }

    @Test
    void onlyAdministratorsListUsers() {
        ResponseEntity<Map<String, Object>> attendee = list(TestTokens.forUser(UUID.randomUUID(), "ATTENDEE"));
        ResponseEntity<Map<String, Object>> admin = list(TestTokens.forUser(UUID.randomUUID(), "ADMIN"));

        assertThat(attendee.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(attendee.getBody()).containsEntry("code", "ACCESS_DENIED");
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.getBody()).containsKey("content");
    }

    @Test
    void locationUsesForwardedPublicHost() {
        ResponseEntity<Map<String, Object>> created = client().put().uri("/api/users/me")
                .header("Authorization", "Bearer " + TestTokens.withEmail(UUID.randomUUID(), uniqueEmail()))
                .header("X-Forwarded-Host", "api.example.com")
                .header("X-Forwarded-Proto", "https")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fullName", "Ada Lovelace"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(JSON_OBJECT);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation().toString()).isEqualTo("https://api.example.com/api/users/me");
    }

    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String token, Object body) {
        RestClient.RequestBodySpec request = client().method(method).uri("/api/users/me")
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
    }

    private ResponseEntity<Map<String, Object>> list(String token) {
        return client().get().uri("/api/users?size=100").header("Authorization", "Bearer " + token)
                .retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
