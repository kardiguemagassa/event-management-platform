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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
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
    private static final AtomicReference<String> forwardedHost = new AtomicReference<>();
    private static final AtomicReference<String> forwardedProto = new AtomicReference<>();
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final HttpServer backend = startBackend();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void routeToFakeBackend(DynamicPropertyRegistry registry) {
        String backendUri = "http://localhost:" + backend.getAddress().getPort();
        registry.add("EVENT_SERVICE_URI", () -> backendUri);
        registry.add("USER_SERVICE_URI", () -> backendUri);
        registry.add("REGISTRATION_SERVICE_URI", () -> backendUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
    }

    @AfterAll
    static void stopBackend() {
        backend.stop(0);
    }

    @BeforeEach
    void resetRecordedRequests() {
        receivedPaths.clear();
        forwardedHost.set(null);
        forwardedProto.set(null);
        authorization.set(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/events",
            "/api/events/42",
            "/api/users",
            "/api/users/7",
            "/api/registrations/events/3"
    })
    void routesPathUnchangedToBackend(String path) {
        ResponseEntity<Void> response = get(path);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receivedPaths).containsExactly(path);
    }

    @Test
    void forwardsPublicHostAndProtocolToBackend() {
        get("/api/users/7");

        assertThat(forwardedHost.get()).isEqualTo("localhost:" + port);
        assertThat(forwardedProto.get()).isEqualTo("http");
    }

    @Test
    void relaysTheAccessTokenToTheBackend() {
        String token = TestTokens.valid();

        RestClient.create().get()
                .uri("http://localhost:" + port + "/api/users/7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();

        // The services validate the same token again (zero trust).
        assertThat(authorization.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void overridesSpoofedForwardedHeadersSentByClient() {
        RestClient.create().get()
                .uri("http://localhost:" + port + "/api/users/7")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid())
                .header("X-Forwarded-Host", "evil.example")
                .header("X-Forwarded-Proto", "https")
                .retrieve()
                .toBodilessEntity();

        assertThat(forwardedHost.get()).isEqualTo("localhost:" + port);
        assertThat(forwardedProto.get()).isEqualTo("http");
    }

    @Test
    void unroutedApiPathReturnsNotFoundWithoutCallingBackend() {
        ResponseEntity<Void> response = get("/api/unknown");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(receivedPaths).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/unknown", "/users/7", "/events"})
    void pathOutsideTheApiIsDeniedWithoutCallingBackend(String path) {
        ResponseEntity<Void> response = get(path);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    void notFoundRequestsAreLogged(CapturedOutput output) {
        get("/api/unknown");

        assertThat(output).contains("GET /api/unknown -> 404");
    }

    @Test
    void actuatorRequestsAreNotLogged(CapturedOutput output) {
        ResponseEntity<Void> response = get("/actuator/health");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(output).doesNotContain("/actuator/health ->");
    }

    /** Authenticated GET: these tests are about routing, security has its own test class. */
    private ResponseEntity<Void> get(String path) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.valid())
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toBodilessEntity();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                receivedPaths.add(exchange.getRequestURI().getRawPath());
                forwardedHost.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
                forwardedProto.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
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
