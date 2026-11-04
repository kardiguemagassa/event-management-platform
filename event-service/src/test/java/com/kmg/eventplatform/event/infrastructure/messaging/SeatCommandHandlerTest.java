package com.kmg.eventplatform.event.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kmg.eventplatform.event.application.CapacityOutcome;
import com.kmg.eventplatform.event.application.CapacityService;
import com.kmg.eventplatform.event.application.ReservationAttempt;
import com.kmg.eventplatform.event.infrastructure.messaging.SeatMessages.SeatCommand;
import com.kmg.eventplatform.event.infrastructure.messaging.SeatMessages.SeatRejected;
import com.kmg.eventplatform.event.infrastructure.messaging.SeatMessages.SeatReserved;
import com.kmg.eventplatform.messaging.InvalidMessageException;
import com.kmg.eventplatform.messaging.MessageCodec;
import com.kmg.eventplatform.messaging.MessageEnvelope;
import com.kmg.eventplatform.messaging.Outbox;
import com.kmg.eventplatform.messaging.ProcessedMessages;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class SeatCommandHandlerTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID RESERVATION = UUID.randomUUID();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final MessageCodec codec = new MessageCodec(jsonMapper);

    @Mock
    private CapacityService capacity;
    @Mock
    private ProcessedMessages processedMessages;
    @Mock
    private Outbox outbox;

    private SeatCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SeatCommandHandler(capacity, processedMessages, outbox, codec);
    }

    private MessageEnvelope command(String type, UUID eventId, UUID reservationId) {
        return new MessageEnvelope(UUID.randomUUID(), type, Instant.now(),
                jsonMapper.valueToTree(new SeatCommand(eventId, reservationId)));
    }

    private MessageEnvelope firstDelivery(String type, UUID eventId, UUID reservationId) {
        MessageEnvelope envelope = command(type, eventId, reservationId);
        when(processedMessages.markProcessed(envelope.messageId())).thenReturn(true);
        return envelope;
    }

    @Test
    void aReservedSeatIsAnsweredSeatReserved() {
        when(capacity.attemptReservation(EVENT, RESERVATION)).thenReturn(ReservationAttempt.ALREADY_RESERVED);

        handler.handle(firstDelivery(SeatMessages.RESERVE_SEAT, EVENT, RESERVATION));

        verify(outbox).enqueue(SeatMessages.REPLIES_TOPIC, EVENT.toString(), SeatMessages.SEAT_RESERVED,
                new SeatReserved(EVENT, RESERVATION));
    }

    @Test
    void aRefusalIsAnsweredSeatRejectedWithItsReason() {
        when(capacity.attemptReservation(EVENT, RESERVATION)).thenReturn(ReservationAttempt.EVENT_NOT_REGISTRABLE);

        handler.handle(firstDelivery(SeatMessages.RESERVE_SEAT, EVENT, RESERVATION));

        ArgumentCaptor<Object> reply = ArgumentCaptor.forClass(Object.class);
        verify(outbox).enqueue(eq(SeatMessages.REPLIES_TOPIC), eq(EVENT.toString()), eq(SeatMessages.SEAT_REJECTED),
                reply.capture());
        assertThat(reply.getValue()).isEqualTo(new SeatRejected(EVENT, RESERVATION, "EVENT_NOT_REGISTRABLE"));
    }

    @Test
    void aReleaseFreesTheSeatWithoutReply() {
        when(capacity.releaseIfReserved(EVENT, RESERVATION)).thenReturn(CapacityOutcome.RELEASED);

        handler.handle(firstDelivery(SeatMessages.RELEASE_SEAT, EVENT, RESERVATION));

        verify(capacity).releaseIfReserved(EVENT, RESERVATION);
        verifyNoInteractions(outbox);
    }

    @Test
    void aCommandAlreadyHandledIsSkipped() {
        MessageEnvelope envelope = command(SeatMessages.RESERVE_SEAT, EVENT, RESERVATION);
        when(processedMessages.markProcessed(envelope.messageId())).thenReturn(false);

        handler.handle(envelope);

        verifyNoInteractions(capacity, outbox);
    }

    @Test
    void anUnknownTypeIsInvalid() {
        assertThatThrownBy(() -> handler.handle(firstDelivery("TakeAllSeats", EVENT, RESERVATION)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Unknown seat command type TakeAllSeats");
        verifyNoInteractions(capacity, outbox);
    }

    @Test
    void aCommandWithoutEventOrReservationIsInvalid() {
        assertThatThrownBy(() -> handler.handle(firstDelivery(SeatMessages.RESERVE_SEAT, null, RESERVATION)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> handler.handle(firstDelivery(SeatMessages.RESERVE_SEAT, EVENT, null)))
                .isInstanceOf(InvalidMessageException.class);
        verify(capacity, never()).attemptReservation(any(), any());
        verify(outbox, never()).enqueue(anyString(), anyString(), anyString(), any());
    }
}
