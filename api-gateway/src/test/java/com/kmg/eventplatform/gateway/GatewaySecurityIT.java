package com.kmg.eventplatform.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Access control at the edge: who may reach which route, and how refusals look (RFC 9457 + RFC 6750). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
class GatewaySecurityIT {

    private static final FakeBackend BACKEND = FakeBackend.start();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("EVENT_SERVICE_URI", BACKEND::url);
        registry.add("USER_SERVICE_URI", BACKEND::url);
        registry.add("REGISTRATION_SERVICE_URI", BACKEND::url);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetBackend() {
        BACKEND.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/7", "/api/registrations/42"})
    void anonymousCallToAProtectedRouteIsUnauthenticated(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"", "\"instance\":\"" + path + "\"");
        assertThat(BACKEND.requests()).isZero();
    }

    @Test
    void anonymousWriteToTheEventCatalogIsUnauthenticated() throws Exception {
        assertThat(send("POST", "/api/events", null).statusCode()).isEqualTo(401);
        assertThat(BACKEND.requests()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/events", "/api/events/42"})
    void eventCatalogIsPublicForReads(String path) throws Exception {
        assertThat(send("GET", path, null).statusCode()).isEqualTo(200);
        assertThat(BACKEND.paths()).containsExactly(path);
    }

    @Test
    void validTokenReachesTheService() throws Exception {
        assertThat(send("POST", "/api/registrations", TestTokens.valid()).statusCode()).isEqualTo(200);
        assertThat(BACKEND.paths()).containsExactly("/api/registrations");
    }

    static Stream<String> invalidTokens() {
        return Stream.of(
                TestTokens.expired(),
                TestTokens.fromAnotherIssuer(),
                TestTokens.forAnotherAudience(),
                TestTokens.signedByUnknownKey(),
                "not-a-jwt");
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void invalidTokenIsRejectedWithoutCallingTheService(String token) throws Exception {
        HttpResponse<String> response = send("GET", "/api/users/7", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"");
        assertThat(BACKEND.requests()).isZero();
    }

    @Test
    void invalidTokenIsRejectedEvenOnPublicRoutes() throws Exception {
        assertThat(send("GET", "/api/events", TestTokens.expired()).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/events/42/capacity/reserve", "/api/events/42/capacity/release"})
    void capacityRoutesAreClosedAtTheEdgeEvenWithAValidToken(String path) throws Exception {
        HttpResponse<String> response = send("POST", path, TestTokens.valid());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate"))
                .hasValue("Bearer error=\"insufficient_scope\"");
        assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"", "\"instance\":\"" + path + "\"");
        assertThat(BACKEND.requests()).isZero();
    }

    @Test
    void capacityRoutesAskAnonymousCallersToAuthenticate() throws Exception {
        assertThat(send("POST", "/api/events/42/capacity/reserve", null).statusCode()).isEqualTo(401);
        assertThat(BACKEND.requests()).isZero();
    }

    @Test
    void circuitBreakerFallbackIsNotReachableFromOutside() throws Exception {
        assertThat(send("GET", "/fallback/unavailable", TestTokens.valid()).statusCode()).isEqualTo(403);
    }

    @Test
    void healthProbesStayPublic() throws Exception {
        assertThat(send("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, "GET".equals(method)
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
