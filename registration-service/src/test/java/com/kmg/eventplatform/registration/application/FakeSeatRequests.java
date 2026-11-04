package com.kmg.eventplatform.registration.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Records the seat requests, in order, as the outbox would publish them. */
class FakeSeatRequests implements SeatRequests {

    record Request(String type, UUID eventId, UUID reservationId) {
    }

    final List<Request> sent = new ArrayList<>();

    @Override
    public void reserve(UUID eventId, UUID reservationId) {
        sent.add(new Request("reserve", eventId, reservationId));
    }

    @Override
    public void release(UUID eventId, UUID reservationId) {
        sent.add(new Request("release", eventId, reservationId));
    }
}
