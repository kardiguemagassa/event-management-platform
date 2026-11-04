package com.kmg.eventplatform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;

class NoiseFilterTest {

    private final Tracer tracer = mock(Tracer.class);
    private final NoiseFilter filter = new NoiseFilter(provider(tracer));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> provider(Tracer available) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return provider;
    }

    /** As datasource-micrometer does with connection spans off: the parent of a query is a no-op observation. */
    private static Observation.Context query() {
        Observation.Context query = new Observation.Context();
        query.setParentObservation(Observation.NOOP);
        return query;
    }

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
    void sqlQueryOutsideOfAnyTraceIsDropped() {
        assertThat(filter.test("jdbc.query", query())).isFalse();
        assertThat(filter.test("jdbc.connection", new Observation.Context())).isFalse();
    }

    @Test
    void sqlQueryOfARequestOrMessageStaysInItsTrace() {
        when(tracer.currentSpan()).thenReturn(mock(Span.class));

        assertThat(filter.test("jdbc.query", query())).isTrue();
    }

    @Test
    void sqlQueryUnderAnObservationStaysInItsTrace() {
        Observation.Context query = new Observation.Context();
        query.setParentObservation(mock(Observation.class));

        assertThat(filter.test("jdbc.query", query)).isTrue();
    }

    @Test
    void withoutTracingSqlIsKeptForItsMetrics() {
        assertThat(new NoiseFilter(provider(null)).test("jdbc.query", query())).isTrue();
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
