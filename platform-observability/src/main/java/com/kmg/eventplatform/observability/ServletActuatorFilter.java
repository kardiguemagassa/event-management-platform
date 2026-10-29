package com.kmg.eventplatform.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** Servlet services: no trace for actuator requests (health probes every few seconds). */
final class ServletActuatorFilter implements ObservationPredicate {

    static final String ACTUATOR_PATH = "/actuator";

    @Override
    public boolean test(String name, Observation.Context context) {
        return !(context instanceof ServerRequestObservationContext server
                && server.getCarrier() != null
                && server.getCarrier().getRequestURI().startsWith(ACTUATOR_PATH));
    }
}
