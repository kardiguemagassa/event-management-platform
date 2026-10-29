package com.kmg.eventplatform.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/** Reactive gateway: no trace for actuator requests (health probes every few seconds). */
final class ReactiveActuatorFilter implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        return !(context instanceof ServerRequestObservationContext server
                && server.getCarrier() != null
                && server.getCarrier().getPath().value().startsWith(ServletActuatorFilter.ACTUATOR_PATH));
    }
}
