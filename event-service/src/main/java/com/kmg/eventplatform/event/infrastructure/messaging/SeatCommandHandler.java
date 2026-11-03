package com.kmg.eventplatform.event.infrastructure.messaging;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles a seat command in one transaction: the message is recorded as processed, the seat is taken or freed, and
 * the reply is written to the outbox. All of it is committed, or none of it and the command is delivered again.
 * <p>
 * Recording the message matters beyond duplicate replies: a {@code ReserveSeat} delivered again after its
 * {@code ReleaseSeat} would otherwise take the seat back.
 */
@Component
public class SeatCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(SeatCommandHandler.class);

    private final CapacityService capacity;
    private final ProcessedMessages processedMessages;
    private final Outbox outbox;
    private final MessageCodec codec;

    public SeatCommandHandler(CapacityService capacity, ProcessedMessages processedMessages, Outbox outbox,
                              MessageCodec codec) {
        this.capacity = capacity;
        this.processedMessages = processedMessages;
        this.outbox = outbox;
        this.codec = codec;
    }

    /**
     * @throws InvalidMessageException for an unknown type or an incomplete payload: sent to the dead letter topic
     */
    @Transactional
    public void handle(MessageEnvelope envelope) {
        if (!processedMessages.markProcessed(envelope.messageId())) {
            log.debug("Seat command {} already handled, skipped", envelope.messageId());
            return;
        }
        SeatCommand command = codec.payload(envelope, SeatCommand.class);
        if (command.eventId() == null || command.reservationId() == null) {
            throw new InvalidMessageException("Seat command " + envelope.messageId() + " without event or reservation");
        }
        switch (envelope.type()) {
            case SeatMessages.RESERVE_SEAT -> reply(command, capacity.attemptReservation(command.eventId(),
                    command.reservationId()));
            case SeatMessages.RELEASE_SEAT -> release(command);
            default -> throw new InvalidMessageException("Unknown seat command type " + envelope.type());
        }
    }

    private void reply(SeatCommand command, ReservationAttempt attempt) {
        String key = command.eventId().toString();
        if (attempt.isReserved()) {
            outbox.enqueue(SeatMessages.REPLIES_TOPIC, key, SeatMessages.SEAT_RESERVED,
                    new SeatReserved(command.eventId(), command.reservationId()));
        } else {
            outbox.enqueue(SeatMessages.REPLIES_TOPIC, key, SeatMessages.SEAT_REJECTED,
                    new SeatRejected(command.eventId(), command.reservationId(), attempt.rejectionCode()));
        }
        // Ids only, no personal data, as on the HTTP path.
        log.info("Seat {} for reservation {} on event {}", attempt, command.reservationId(), command.eventId());
    }

    private void release(SeatCommand command) {
        CapacityOutcome outcome = capacity.releaseIfReserved(command.eventId(), command.reservationId());
        log.info("Seat {} for reservation {} on event {}", outcome, command.reservationId(), command.eventId());
    }
}
