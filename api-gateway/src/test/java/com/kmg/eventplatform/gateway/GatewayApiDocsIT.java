package com.kmg.eventplatform.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    void swaggerUiEntryPointLeadsToTheSignInReadyUi() throws Exception {
        // What a browser does: /swagger-ui.html redirects to the UI, which must be served (not denied)
        HttpResponse<Void> redirect = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/swagger-ui.html")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(redirect.statusCode()).isEqualTo(HttpStatus.FOUND.value());
        String ui = URI.create(redirect.headers().firstValue("Location").orElseThrow()).getPath();

        ResponseEntity<String> page = get(ui);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("swagger-ui");

        // The UI initializes OAuth2 with the public client of the realm
        String initializer = ui.substring(0, ui.lastIndexOf('/') + 1) + "swagger-initializer.js";
        assertThat(get(initializer).getBody()).contains("event-platform-web");
    }

    @Test
    void keycloakRedirectsBackToAServedPage() {
        String config = get("/v3/api-docs/swagger-config").getBody();
        Matcher redirectUrl = Pattern.compile("\"oauth2RedirectUrl\":\"([^\"]+)\"").matcher(config);
        assertThat(redirectUrl.find()).isTrue();
        String path = URI.create(redirectUrl.group(1)).getPath();

        assertThat(path).endsWith("/oauth2-redirect.html");
        assertThat(get(path).getStatusCode()).isEqualTo(HttpStatus.OK);
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
