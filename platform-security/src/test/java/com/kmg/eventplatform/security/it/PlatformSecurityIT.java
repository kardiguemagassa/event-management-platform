package com.kmg.eventplatform.security.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.kmg.eventplatform.security.test.TestTokens;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The chassis in a real servlet container, with real signed tokens and the real token validation. */
@SpringBootTest(classes = SecuredTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformSecurityIT {

    @DynamicPropertySource
    static void tokenValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestTokens::jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestTokens.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> TestTokens.AUDIENCE);
        // springdoc is on the test classpath for PlatformOpenApi only: no API documentation in this context
        registry.add("springdoc.api-docs.enabled", () -> "false");
    }

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void publicEndpointNeedsNoToken() throws Exception {
        assertThat(get("/public", null).statusCode()).isEqualTo(200);
    }

    @Test
    void anonymousCallIsAnUnauthenticatedProblem() throws Exception {
        HttpResponse<String> response = get("/me", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"", "\"instance\":\"/me\"");
    }

    @Test
    void validTokenAuthenticatesItsSubject() throws Exception {
        UUID user = UUID.randomUUID();

        HttpResponse<String> response = get("/me", TestTokens.forUser(user, "ATTENDEE"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(user.toString());
    }

    static Stream<String> invalidTokens() {
        return Stream.of(TestTokens.expired(), TestTokens.forAnotherAudience(), TestTokens.fromAnotherIssuer(),
                TestTokens.signedByUnknownKey(), "not-a-jwt");
    }

    @ParameterizedTest
    @MethodSource("invalidTokens")
    void invalidTokenIsRejected(String token) throws Exception {
        HttpResponse<String> response = get("/me", token);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"invalid_token\"");
        assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"").doesNotContain("expired");
    }

    @Test
    void realmRolesAreMappedToSpringRoles() throws Exception {
        assertThat(get("/seats", TestTokens.forUser(UUID.randomUUID(), "SEAT_MANAGER")).statusCode())
                .isEqualTo(200);
    }

    @Test
    void missingRoleIsAnAccessDeniedProblem() throws Exception {
        HttpResponse<String> response = get("/seats", TestTokens.forUser(UUID.randomUUID(), "ATTENDEE"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"insufficient_scope\"");
        assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"", "\"instance\":\"/seats\"");
    }

    @Test
    void refusalRaisedByAControllerIsNotAServerError() throws Exception {
        HttpResponse<String> response = get("/refused-by-the-controller", TestTokens.forUser(UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"ACCESS_DENIED\"");
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
