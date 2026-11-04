package com.kmg.eventplatform.registration.infrastructure.messaging;

import com.kmg.eventplatform.messaging.InvalidMessageException;
import com.kmg.eventplatform.messaging.MessageCodec;
import com.kmg.eventplatform.messaging.MessageEnvelope;
import com.kmg.eventplatform.messaging.ProcessedMessages;
import com.kmg.eventplatform.registration.application.RegistrationService;
import com.kmg.eventplatform.registration.infrastructure.messaging.SeatMessages.SeatReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles a seat reply in one transaction: the message is recorded as processed and the registration confirmed or
 * rejected. All of it is committed, or none of it and the reply is delivered again.
 */
@Component
public class SeatReplyHandler {

    private static final Logger log = LoggerFactory.getLogger(SeatReplyHandler.class);

    private final RegistrationService registrations;
    private final ProcessedMessages processedMessages;
    private final MessageCodec codec;

    public SeatReplyHandler(RegistrationService registrations, ProcessedMessages processedMessages,
                            MessageCodec codec) {
        this.registrations = registrations;
        this.processedMessages = processedMessages;
        this.codec = codec;
    }

    /**
     * @throws InvalidMessageException for an unknown type or an incomplete payload: sent to the dead letter topic
     */
    @Transactional
    public void handle(MessageEnvelope envelope) {
        if (!processedMessages.markProcessed(envelope.messageId())) {
            log.debug("Seat reply {} already handled, skipped", envelope.messageId());
            return;
        }
        SeatReply reply = codec.payload(envelope, SeatReply.class);
        if (reply.reservationId() == null) {
            throw new InvalidMessageException("Seat reply " + envelope.messageId() + " without reservation");
        }
        switch (envelope.type()) {
            case SeatMessages.SEAT_RESERVED -> registrations.seatReserved(reply.reservationId());
            case SeatMessages.SEAT_REJECTED -> {
                if (reply.reason() == null) {
                    throw new InvalidMessageException("Seat refusal " + envelope.messageId() + " without reason");
                }
                registrations.seatRejected(reply.reservationId(), reply.reason());
            }
            default -> throw new InvalidMessageException("Unknown seat reply type " + envelope.type());
        }
    }
}
