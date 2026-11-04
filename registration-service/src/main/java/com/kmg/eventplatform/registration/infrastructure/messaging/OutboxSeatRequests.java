package com.kmg.eventplatform.registration.infrastructure.messaging;

import com.kmg.eventplatform.messaging.Outbox;
import com.kmg.eventplatform.registration.application.SeatRequests;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages.SeatCommand;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The seat requests as seat commands, written to the outbox in the transaction of the registration change: the
 * request is sent if and only if the change is committed.
 */
@Component
class OutboxSeatRequests implements SeatRequests {

    private final Outbox outbox;

    OutboxSeatRequests(Outbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void reserve(UUID eventId, UUID reservationId) {
        send(SeatMessages.RESERVE_SEAT, eventId, reservationId);
    }

    @Override
    public void release(UUID eventId, UUID reservationId) {
        send(SeatMessages.RELEASE_SEAT, eventId, reservationId);
    }

    private void send(String type, UUID eventId, UUID reservationId) {
        outbox.enqueue(SeatMessages.COMMANDS_TOPIC, eventId.toString(), type, new SeatCommand(eventId, reservationId));
    }
}
