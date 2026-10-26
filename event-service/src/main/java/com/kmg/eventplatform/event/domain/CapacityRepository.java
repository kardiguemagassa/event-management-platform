package com.kmg.eventplatform.event.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Atomic SQL for capacity reservation. Concurrency is arbitrated by PostgreSQL itself:
 * no application lock, no read-modify-write in Java.
 */
public interface CapacityRepository extends Repository<Event, UUID> {

    /**
     * Records a reservation. ON CONFLICT DO NOTHING (instead of catching a duplicate key error)
     * because in PostgreSQL any SQL error aborts the whole transaction.
     *
     * @return 1 if recorded, 0 if this reservation id already exists (replay)
     */
    @Modifying
    @Query(value = """
            INSERT INTO capacity_reservations (reservation_id, event_id, created_at)
            VALUES (:reservationId, :eventId, :now)
            ON CONFLICT (reservation_id) DO NOTHING
            """, nativeQuery = true)
    int insertReservation(UUID reservationId, UUID eventId, Instant now);

    @Query(value = "SELECT event_id FROM capacity_reservations WHERE reservation_id = :reservationId",
            nativeQuery = true)
    Optional<UUID> findReservedEventId(UUID reservationId);

    /**
     * Takes one seat only if the event is still registrable and not full: a single conditional
     * UPDATE, so two concurrent requests can never both take the last seat. The version bump
     * makes concurrent entity updates (PUT, DELETE) fail with an optimistic locking conflict.
     *
     * @return 1 if a seat was taken, 0 otherwise
     */
    @Modifying
    @Query(value = """
            UPDATE events
            SET reserved_seats = reserved_seats + 1, version = version + 1
            WHERE id = :eventId
              AND status = 'PUBLISHED'
              AND start_at > :now
              AND reserved_seats < capacity
            """, nativeQuery = true)
    int incrementReservedSeats(UUID eventId, Instant now);

    @Modifying
    @Query(value = "DELETE FROM capacity_reservations WHERE reservation_id = :reservationId AND event_id = :eventId",
            nativeQuery = true)
    int deleteReservation(UUID reservationId, UUID eventId);

    @Modifying
    @Query(value = """
            UPDATE events
            SET reserved_seats = reserved_seats - 1, version = version + 1
            WHERE id = :eventId
            """, nativeQuery = true)
    int decrementReservedSeats(UUID eventId);
}
