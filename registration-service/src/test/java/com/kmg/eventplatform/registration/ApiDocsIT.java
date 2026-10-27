package com.kmg.eventplatform.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@Import(TestcontainersConfiguration.class)
class ApiDocsIT {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() { };

    @Value("${local.server.port}")
    private int port;

    @Test
    @SuppressWarnings("unchecked")
    void exposesOpenApiContract() {
        Map<String, Object> doc = RestClient.create("http://localhost:" + port)
                .get().uri("/v3/api-docs").retrieve().body(JSON_OBJECT);

        assertThat((String) doc.get("openapi")).startsWith("3.0");
        assertThat((Map<String, Object>) doc.get("info")).containsEntry("title", "Registration Service API");

        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKeys("/api/registrations", "/api/registrations/{id}",
                "/api/registrations/{id}/cancel", "/api/registrations/events/{eventId}",
                "/api/registrations/users/{userId}");
        assertThat(paths.keySet()).allMatch(path -> path.startsWith("/api/"));

        Map<String, Object> register = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/registrations"))
                .get("post");
        assertThat((Map<String, Object>) register.get("responses")).containsKeys("201", "400", "404", "409", "503");

        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        assertThat((Map<String, Object>) components.get("responses"))
                .containsKeys("BadRequest", "NotFound", "Conflict", "ServiceUnavailable");
    }
}
