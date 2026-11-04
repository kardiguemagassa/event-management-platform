package com.kmg.eventplatform.registration.infrastructure.messaging;

import com.kmg.eventplatform.messaging.Outbox;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages.SeatCommand;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxSeatRequestsTest {

    private final Outbox outbox = mock(Outbox.class);
    private final OutboxSeatRequests seats = new OutboxSeatRequests(outbox);
    private final UUID eventId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    @Test
    void seatRequestsAreSeatCommandsKeyedByTheEvent() {
        seats.reserve(eventId, reservationId);
        seats.release(eventId, reservationId);

        verify(outbox).enqueue(SeatMessages.COMMANDS_TOPIC, eventId.toString(), SeatMessages.RESERVE_SEAT,
                new SeatCommand(eventId, reservationId));
        verify(outbox).enqueue(SeatMessages.COMMANDS_TOPIC, eventId.toString(), SeatMessages.RELEASE_SEAT,
                new SeatCommand(eventId, reservationId));
    }
}
