package com.kmg.eventplatform.registration.infrastructure.messaging;

import com.kmg.eventplatform.messaging.InvalidMessageException;
import com.kmg.eventplatform.messaging.MessageCodec;
import com.kmg.eventplatform.messaging.MessageEnvelope;
import com.kmg.eventplatform.messaging.ProcessedMessages;
import com.kmg.eventplatform.registration.application.RegistrationService;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages.SeatReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeatReplyHandlerTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID REGISTRATION = UUID.randomUUID();

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Mock
    private RegistrationService registrations;
    @Mock
    private ProcessedMessages processedMessages;

    private SeatReplyHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SeatReplyHandler(registrations, processedMessages, new MessageCodec(jsonMapper));
    }

    private MessageEnvelope reply(String type, UUID reservationId, String reason) {
        return new MessageEnvelope(UUID.randomUUID(), type, Instant.now(),
                jsonMapper.valueToTree(new SeatReply(EVENT, reservationId, reason)));
    }

    private MessageEnvelope firstDelivery(String type, UUID reservationId, String reason) {
        MessageEnvelope envelope = reply(type, reservationId, reason);
        when(processedMessages.markProcessed(envelope.messageId())).thenReturn(true);
        return envelope;
    }

    @Test
    void aReservedSeatConfirmsTheRegistration() {
        handler.handle(firstDelivery(SeatMessages.SEAT_RESERVED, REGISTRATION, null));

        verify(registrations).seatReserved(REGISTRATION);
    }

    @Test
    void aRefusedSeatRejectsTheRegistrationWithItsReason() {
        handler.handle(firstDelivery(SeatMessages.SEAT_REJECTED, REGISTRATION, "EVENT_NOT_REGISTRABLE"));

        verify(registrations).seatRejected(REGISTRATION, "EVENT_NOT_REGISTRABLE");
    }

    @Test
    void aReplyAlreadyHandledIsSkipped() {
        MessageEnvelope envelope = reply(SeatMessages.SEAT_RESERVED, REGISTRATION, null);
        when(processedMessages.markProcessed(envelope.messageId())).thenReturn(false);

        handler.handle(envelope);

        verifyNoInteractions(registrations);
    }

    @Test
    void incompleteOrUnknownRepliesAreInvalid() {
        assertThatThrownBy(() -> handler.handle(firstDelivery(SeatMessages.SEAT_RESERVED, null, null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> handler.handle(firstDelivery(SeatMessages.SEAT_REJECTED, REGISTRATION, null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> handler.handle(firstDelivery("SeatStolen", REGISTRATION, null)))
                .isInstanceOf(InvalidMessageException.class)
                .hasMessage("Unknown seat reply type SeatStolen");
        verifyNoInteractions(registrations);
    }
}
