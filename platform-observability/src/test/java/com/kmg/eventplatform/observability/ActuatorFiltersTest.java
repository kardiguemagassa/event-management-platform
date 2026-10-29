package com.kmg.eventplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import java.util.HashMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActuatorFiltersTest {

    @Nested
    class Servlet {

        private final ServletActuatorFilter filter = new ServletActuatorFilter();

        @Test
        void healthProbesAreDropped() {
            assertThat(filter.test("http.server.requests", request("/actuator/health/readiness"))).isFalse();
        }

        @Test
        void apiRequestsAreKept() {
            assertThat(filter.test("http.server.requests", request("/api/events"))).isTrue();
        }

        @Test
        void otherContextsAreKept() {
            assertThat(filter.test("jdbc.query", new Observation.Context())).isTrue();
            assertThat(filter.test("http.server.requests",
                    new org.springframework.http.server.observation.ServerRequestObservationContext(null, null)))
                    .isTrue();
        }

        private static org.springframework.http.server.observation.ServerRequestObservationContext request(String path) {
            return new org.springframework.http.server.observation.ServerRequestObservationContext(
                    new MockHttpServletRequest("GET", path), new MockHttpServletResponse());
        }
    }

    @Nested
    class Reactive {

        private final ReactiveActuatorFilter filter = new ReactiveActuatorFilter();

        @Test
        void healthProbesAreDropped() {
            assertThat(filter.test("http.server.requests", request("/actuator/health/readiness"))).isFalse();
        }

        @Test
        void apiRequestsAreKept() {
            assertThat(filter.test("http.server.requests", request("/api/registrations/me"))).isTrue();
        }

        @Test
        void otherContextsAreKept() {
            assertThat(filter.test("http.client.requests", new Observation.Context())).isTrue();
            assertThat(filter.test("http.server.requests",
                    new org.springframework.http.server.reactive.observation.ServerRequestObservationContext(
                            null, new MockServerHttpResponse(), new HashMap<>())))
                    .isTrue();
        }

        private static org.springframework.http.server.reactive.observation.ServerRequestObservationContext request(
                String path) {
            return new org.springframework.http.server.reactive.observation.ServerRequestObservationContext(
                    MockServerHttpRequest.get(path).build(), new MockServerHttpResponse(), new HashMap<>());
        }
    }
}
