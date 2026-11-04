package com.kmg.eventplatform.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.Tracer;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.observation.ClientRequestObservationContext;

/**
 * Keeps traces about the business: drops what runs every few seconds or inside every request and would bury the
 * useful traces (sampling is 100 % in development).
 * <ul>
 *   <li>Spring Security observations: one span per filter of the chain, for every request.</li>
 *   <li>Calls to Eureka: registry fetches and heartbeats of every instance.</li>
 *   <li>SQL queries outside of any trace: the outbox relay polls its table every 200 ms, the gauges read the
 *       database at each metrics push, scheduled purges, migrations and health checks run on their own. Each would
 *       be a trace of its own (about 5 per second and per service for the relay alone). A query made while handling
 *       a request or a Kafka message runs inside that trace and stays in it.</li>
 * </ul>
 * Health probes are dropped by the server-side filters ({@link ServletActuatorFilter}, {@link ReactiveActuatorFilter}).
 */
final class NoiseFilter implements ObservationPredicate {

    static final String SPRING_SECURITY_PREFIX = "spring.security.";
    static final String EUREKA_PATH = "/eureka/";
    /** Observations of datasource-micrometer ({@code jdbc.query}, {@code jdbc.connection}...). */
    static final String JDBC_PREFIX = "jdbc.";

    private final ObjectProvider<Tracer> tracer;

    NoiseFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    public boolean test(String name, Observation.Context context) {
        if (name.startsWith(SPRING_SECURITY_PREFIX)) {
            return false;
        }
        if (name.startsWith(JDBC_PREFIX) && outsideOfAnyTrace(context)) {
            return false;
        }
        return !(context instanceof ClientRequestObservationContext client && callsEureka(client));
    }

    /**
     * datasource-micrometer gives a query the observation of its connection as parent, a no-op one when connection
     * spans are off: the span of the query then joins the current span of the thread. Without a current span (relay
     * or scheduler thread, untraced health probe), it would start a trace of its own. Without a tracer, nothing is
     * filtered: the JDBC metrics stay.
     */
    private boolean outsideOfAnyTrace(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        if (parent instanceof Observation observation && !observation.isNoop()) {
            return false;
        }
        Tracer current = tracer.getIfAvailable();
        return current != null && current.currentSpan() == null;
    }

    private static boolean callsEureka(ClientRequestObservationContext client) {
        if (client.getCarrier() == null) {
            return false;
        }
        URI uri = client.getCarrier().getURI();
        return uri.getPath() != null && uri.getPath().startsWith(EUREKA_PATH);
    }
}
