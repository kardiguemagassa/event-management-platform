package com.kmg.eventplatform.event.infrastructure.messaging;

import java.util.UUID;

/**
 * The seat contract of the registration saga, as event-service reads and writes it. registration-service keeps its
 * own copy (no shared library of business types): a field added by one side is ignored by the other.
 * <p>
 * Every message is keyed by the event id: the commands and replies of one event stay in order on one partition.
 */
public final class SeatMessages {

    /** Written by registration-service: {@link #RESERVE_SEAT}, {@link #RELEASE_SEAT}. */
    public static final String COMMANDS_TOPIC = "registration.seat-commands";
    /** Written by event-service: {@link #SEAT_RESERVED}, {@link #SEAT_REJECTED}. */
    public static final String REPLIES_TOPIC = "event.seat-replies";
    public static final int PARTITIONS = 3;

    public static final String RESERVE_SEAT = "ReserveSeat";
    public static final String RELEASE_SEAT = "ReleaseSeat";
    public static final String SEAT_RESERVED = "SeatReserved";
    public static final String SEAT_REJECTED = "SeatRejected";

    private SeatMessages() {
    }

    /**
     * Payload of {@code ReserveSeat} and {@code ReleaseSeat}.
     *
     * @param reservationId the registration id: the idempotency key of the seat
     */
    public record SeatCommand(UUID eventId, UUID reservationId) {
    }

    public record SeatReserved(UUID eventId, UUID reservationId) {
    }

    /**
     * @param reason the code of the matching HTTP problem: {@code EVENT_FULL}, {@code EVENT_NOT_REGISTRABLE},
     *               {@code EVENT_NOT_FOUND}
     */
    public record SeatRejected(UUID eventId, UUID reservationId, String reason) {
    }
}
