package com.kmg.eventplatform.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing failures through the real gateway stack: user-service points to a closed port, event-service is
 * load-balanced over one dead and one live instance, registration-service is a live but possibly slow backend.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class GatewayResilienceIT {

    private static final FakeBackend BACKEND = FakeBackend.start();
    private static final int CLOSED_PORT = closedPort();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URI", () -> "http://localhost:" + CLOSED_PORT);
        registry.add("REGISTRATION_SERVICE_URI", BACKEND::url);
        registry.add("spring.cloud.discovery.client.simple.instances.event-service[0].uri",
                () -> "http://localhost:" + CLOSED_PORT);
        registry.add("spring.cloud.discovery.client.simple.instances.event-service[1].uri", BACKEND::url);
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @Test
    void unreachableServiceGivesA503Problem() throws Exception {
        HttpResponse<String> response = send("GET", "/api/users/42");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("\"code\":\"SERVICE_UNAVAILABLE\"", "\"instance\":\"/api/users/42\"");
    }

    @Test
    void writesToAnUnreachableServiceAlsoGiveA503Problem() throws Exception {
        assertThat(send("POST", "/api/users").statusCode()).isEqualTo(503);
    }

    @Test
    void readsAreRetriedOnAnotherInstance() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(send("GET", "/api/events/42").statusCode()).isEqualTo(200);
        }
        assertThat(BACKEND.requests()).isEqualTo(4);
    }

    @Test
    void writesAreNeverRetried() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            statuses.add(send("POST", "/api/events").statusCode());
        }
        // Round robin over one dead and one live instance: every other write fails, none is replayed.
        assertThat(statuses).containsOnly(200, 503).filteredOn(status -> status == 200).hasSize(2);
        assertThat(BACKEND.requests()).isEqualTo(2);
    }

    @Test
    void slowAnswerWithinTheResponseTimeoutSucceeds() throws Exception {
        // Would fail if the default 1 s TimeLimiter of Spring Cloud CircuitBreaker were active.
        BACKEND.answerSlowly(Duration.ofMillis(1500));

        assertThat(send("GET", "/api/registrations/42").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, "GET".equals(method)
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
