package com.kmg.eventplatform.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
        assertThat((Map<String, Object>) doc.get("info")).containsEntry("title", "Event Service API");

        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKeys("/api/events", "/api/events/{id}",
                "/api/events/{id}/publish", "/api/events/{id}/cancel");
        assertThat(paths.keySet()).allMatch(path -> path.startsWith("/api/"));

        Map<String, Object> components = (Map<String, Object>) doc.get("components");

        Map<String, Object> securitySchemes = (Map<String, Object>) components.get("securitySchemes");

        assertThat(securitySchemes).containsOnlyKeys("keycloak");

        assertThat(securitySchemes.get("keycloak").toString()).contains("/protocol/openid-connect/auth");

        assertThat((List<?>) doc.get("security")).hasSize(1);
        assertThat((Map<String, Object>) components.get("responses"))
                .containsKeys("BadRequest", "NotFound", "Conflict");
    }
}
