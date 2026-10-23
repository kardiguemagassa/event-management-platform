package com.kmg.eventplatform.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerIT {

    @Value("${local.server.port}")
    private int port;

    @Test
    void healthIsUp() {
        ResponseEntity<String> response = client().get().uri("/actuator/health")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void registryEndpointIsAvailable() {
        ResponseEntity<String> response = client().get().uri("/eureka/apps")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("applications");
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
