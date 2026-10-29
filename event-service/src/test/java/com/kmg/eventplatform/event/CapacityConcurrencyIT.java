package com.kmg.eventplatform.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.kmg.eventplatform.security.test.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Proves the capacity guarantees under real concurrency (HTTP + PostgreSQL):
 * no overbooking, idempotent replays, consistent counters.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@Import(TestcontainersConfiguration.class)
class CapacityConcurrencyIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    /** The organizer creates the events; registration-service's service account reserves the seats. */
    private static final String ORGANIZER = TestTokens.forUser(UUID.randomUUID(), "ORGANIZER");
    private static final String SEAT_MANAGER = TestTokens.forUser(TestTokens.SERVICE_ACCOUNT, "SEAT_MANAGER");

    @DynamicPropertySource
    static void tokenValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
    }

    @Value("${local.server.port}")
    private int port;

    private ResponseEntity<Map<String, Object>> send(HttpMethod method, String path, Object body) {
        String token = path.contains("/capacity/") ? SEAT_MANAGER : ORGANIZER;
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path)
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().onStatus(HttpStatusCode::isError, (req, res) -> { }).toEntity(JSON_OBJECT);
    }

    private String publishedEvent(int capacity) {
        Map<String, Object> body = Map.of("title", "Concurrency", "location", "Paris",
                "startAt", "2099-06-01T18:00:00Z", "capacity", capacity);
        String id = (String) send(HttpMethod.POST, "/api/events", body).getBody().get("id");
        send(HttpMethod.POST, "/api/events/" + id + "/publish", null);
        return id;
    }

    private ResponseEntity<Map<String, Object>> capacity(String eventId, String action, UUID reservationId) {
        return send(HttpMethod.POST, "/api/events/" + eventId + "/capacity/" + action,
                Map.of("reservationId", reservationId.toString()));
    }

    private Map<String, Object> event(String eventId) {
        return send(HttpMethod.GET, "/api/events/" + eventId, null).getBody();
    }

    /** Starts all calls at the same instant to maximize contention. */
    private static <T> List<T> concurrently(int count, Callable<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @Test
    void twentyConcurrentReservationsNeverOverbookFiveSeats() throws Exception {
        String eventId = publishedEvent(5);

        List<ResponseEntity<Map<String, Object>>> responses =
                concurrently(20, () -> capacity(eventId, "reserve", UUID.randomUUID()));

        assertThat(responses).filteredOn(r -> r.getStatusCode().value() == 200).hasSize(5);
        assertThat(responses).filteredOn(r -> r.getStatusCode().value() == 409).hasSize(15)
                .allSatisfy(r -> assertThat(r.getBody()).containsEntry("code", "EVENT_FULL"));
        assertThat(event(eventId)).containsEntry("reservedSeats", 5).containsEntry("availableSeats", 0);
    }

    @Test
    void concurrentReplaysOfTheSameReservationTakeOneSeat() throws Exception {
        String eventId = publishedEvent(5);
        UUID reservationId = UUID.randomUUID();

        List<ResponseEntity<Map<String, Object>>> responses =
                concurrently(10, () -> capacity(eventId, "reserve", reservationId));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        assertThat(responses).filteredOn(r -> "RESERVED".equals(r.getBody().get("outcome"))).hasSize(1);
        assertThat(event(eventId)).containsEntry("reservedSeats", 1);
    }

    @Test
    void releaseIsIdempotentAndKeepsCountersConsistent() {
        String eventId = publishedEvent(5);
        UUID reservationId = UUID.randomUUID();
        capacity(eventId, "reserve", reservationId);
        assertThat(send(HttpMethod.DELETE, "/api/events/" + eventId, null).getBody())
                .as("an event with reservations cannot be deleted")
                .containsEntry("code", "EVENT_HAS_RESERVATIONS");

        assertThat(capacity(eventId, "release", reservationId).getBody()).containsEntry("outcome", "RELEASED");
        assertThat(capacity(eventId, "release", reservationId).getBody()).containsEntry("outcome", "NOT_RESERVED");

        assertThat(event(eventId)).containsEntry("reservedSeats", 0).containsEntry("availableSeats", 5);
        assertThat(send(HttpMethod.DELETE, "/api/events/" + eventId, null).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void draftEventIsNotRegistrable() {
        Map<String, Object> body = Map.of("title", "Draft", "location", "Paris",
                "startAt", "2099-06-01T18:00:00Z", "capacity", 5);
        String eventId = (String) send(HttpMethod.POST, "/api/events", body).getBody().get("id");

        ResponseEntity<Map<String, Object>> response = capacity(eventId, "reserve", UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("code", "EVENT_NOT_REGISTRABLE");
        assertThat(event(eventId)).containsEntry("reservedSeats", 0);
    }
}
