package com.kmg.eventplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** The gateway serves a single Swagger UI aggregating the API docs of every service. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class GatewayApiDocsIT {

    private static final FakeBackend backend = FakeBackend.start();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void routeToFakeBackend(DynamicPropertyRegistry registry) {
        registry.add("EVENT_SERVICE_URI", backend::url);
        registry.add("USER_SERVICE_URI", backend::url);
        registry.add("REGISTRATION_SERVICE_URI", backend::url);
    }

    @BeforeEach
    void resetBackend() {
        backend.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"user-service", "event-service", "registration-service"})
    void proxiesEachServiceApiDocsToItsOpenApiEndpoint(String service) {
        ResponseEntity<String> response = get("/api-docs/" + service);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(backend.paths()).containsExactly("/v3/api-docs");
    }

    @Test
    void swaggerConfigListsEveryService() {
        ResponseEntity<String> response = get("/v3/api-docs/swagger-config");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(
                "\"name\":\"User\"", "\"url\":\"/api-docs/user-service\"",
                "\"name\":\"Event\"", "\"url\":\"/api-docs/event-service\"",
                "\"name\":\"Registration\"", "\"url\":\"/api-docs/registration-service\"");
        assertThat(backend.requests()).isZero();
    }

    @Test
    void servesSwaggerUi() {
        ResponseEntity<String> response = get("/webjars/swagger-ui/index.html");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("swagger-ui");
    }

    @Test
    void gatewayOwnApiDocsExposeNoInternalEndpoint() {
        ResponseEntity<String> response = get("/v3/api-docs");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("/fallback");
    }

    private ResponseEntity<String> get(String path) {
        return RestClient.create().get()
                .uri("http://localhost:" + port + path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
    }
}
