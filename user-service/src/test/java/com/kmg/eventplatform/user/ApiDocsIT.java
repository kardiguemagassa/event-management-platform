package com.kmg.eventplatform.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
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
        Map<String, Object> doc = client().get().uri("/v3/api-docs").retrieve().body(JSON_OBJECT);

        assertThat((String) doc.get("openapi")).startsWith("3.0");
        Map<String, Object> info = (Map<String, Object>) doc.get("info");
        assertThat(info).containsEntry("title", "User Service API");

        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKeys("/api/users", "/api/users/{id}", "/api/users/me");
        assertThat(paths.keySet()).allMatch(path -> path.startsWith("/api/"));

        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        Map<String, Object> responses = (Map<String, Object>) components.get("responses");
        assertThat(responses).containsKeys("BadRequest", "NotFound", "Conflict");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertThat(schemas).containsKey("ProblemDetail");
    }

    @Test
    void swaggerUiIsAvailable() {
        HttpStatusCode status = client().get().uri("/swagger-ui/index.html")
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toBodilessEntity()
                .getStatusCode();

        assertThat(status).isEqualTo(HttpStatus.OK);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
