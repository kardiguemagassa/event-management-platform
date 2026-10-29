package com.kmg.eventplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;

class NoiseFilterTest {

    private final NoiseFilter filter = new NoiseFilter();

    @Test
    void springSecurityObservationsAreDropped() {
        assertThat(filter.test("spring.security.filterchains", new Observation.Context())).isFalse();
        assertThat(filter.test("spring.security.authorizations", new Observation.Context())).isFalse();
    }

    @Test
    void callsToEurekaAreDropped() {
        assertThat(filter.test("http.client.requests", client("http://discovery-server:8761/eureka/apps/"))).isFalse();
    }

    @Test
    void callsBetweenServicesAreKept() {
        assertThat(filter.test("http.client.requests", client("http://event-service/api/events/42"))).isTrue();
    }

    @Test
    void anyOtherObservationIsKept() {
        assertThat(filter.test("http.server.requests", new Observation.Context())).isTrue();
        assertThat(filter.test("http.client.requests", new ClientRequestObservationContext(null))).isTrue();
        assertThat(filter.test("http.client.requests", client("mailto:ops@example.com"))).isTrue();
    }

    private static ClientRequestObservationContext client(String uri) {
        return new ClientRequestObservationContext(new MockClientHttpRequest(HttpMethod.GET, URI.create(uri)));
    }
}
