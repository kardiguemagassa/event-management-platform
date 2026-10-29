package com.kmg.eventplatform.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import java.net.URI;
import org.springframework.http.client.observation.ClientRequestObservationContext;

/**
 * Keeps traces about the business: drops what runs every few seconds or inside every request and would bury the
 * useful traces (sampling is 100 % in development).
 * <ul>
 *   <li>Spring Security observations: one span per filter of the chain, for every request.</li>
 *   <li>Calls to Eureka: registry fetches and heartbeats of every instance.</li>
 * </ul>
 * Health probes are dropped by the server-side filters ({@link ServletActuatorFilter}, {@link ReactiveActuatorFilter}).
 */
final class NoiseFilter implements ObservationPredicate {

    static final String SPRING_SECURITY_PREFIX = "spring.security.";
    static final String EUREKA_PATH = "/eureka/";

    @Override
    public boolean test(String name, Observation.Context context) {
        if (name.startsWith(SPRING_SECURITY_PREFIX)) {
            return false;
        }
        return !(context instanceof ClientRequestObservationContext client && callsEureka(client));
    }

    private static boolean callsEureka(ClientRequestObservationContext client) {
        if (client.getCarrier() == null) {
            return false;
        }
        URI uri = client.getCarrier().getURI();
        return uri.getPath() != null && uri.getPath().startsWith(EUREKA_PATH);
    }
}
