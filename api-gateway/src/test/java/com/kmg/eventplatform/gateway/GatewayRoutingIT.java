package com.kmg.eventplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class GatewayRoutingIT {

    private static final List<String> receivedPaths = new CopyOnWriteArrayList<>();
    private static final HttpServer backend = startBackend();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void routeToFakeBackend(DynamicPropertyRegistry registry) {
        String backendUri = "http://localhost:" + backend.getAddress().getPort();
        registry.add("EVENT_SERVICE_URI", () -> backendUri);
        registry.add("USER_SERVICE_URI", () -> backendUri);
        registry.add("REGISTRATION_SERVICE_URI", () -> backendUri);
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @BeforeEach
    void resetRecordedPaths() {
        receivedPaths.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "/events,                     /api/events",
            "/events/42,                  /api/events/42",
            "/events/42/capacity/reserve, /api/events/42/capacity/reserve",
            "/users/7,                    /api/users/7",
            "/registrations/events/3,     /api/registrations/events/3"
    })
    void routesAndRewritesPathToBackend(String gatewayPath, String expectedBackendPath) {
        ResponseEntity<Void> response = get(gatewayPath);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receivedPaths).containsExactly(expectedBackendPath);
    }

    @Test
    void unknownPathReturnsNotFoundWithoutCallingBackend(CapturedOutput output) {
        ResponseEntity<Void> response = get("/unknown");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(receivedPaths).isEmpty();
        assertThat(output).contains("GET /unknown -> 404");
    }

    @Test
    void actuatorRequestsAreNotLogged(CapturedOutput output) {
        ResponseEntity<Void> response = get("/actuator/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(output).doesNotContain("/actuator/health ->");
    }

    private ResponseEntity<Void> get(String path) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toBodilessEntity();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                receivedPaths.add(exchange.getRequestURI().getRawPath());
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
