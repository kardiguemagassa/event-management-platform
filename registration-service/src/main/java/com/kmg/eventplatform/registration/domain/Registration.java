package com.kmg.eventplatform.registration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "registrations")
public class Registration {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RegistrationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Why event-service refused the seat ({@code EVENT_FULL}...), for a REJECTED registration only. */
    @Column(name = "rejection_reason", length = 40)
    private String rejectionReason;

    @Version
    private Long version;

    protected Registration() {
        // for JPA
    }

    private Registration(UUID id, UUID eventId, UUID userId, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.eventId = Objects.requireNonNull(eventId, "eventId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.createdAt = Objects.requireNonNull(now, "now");
        this.status = RegistrationStatus.PENDING;
    }

    /**
     * A new registration waits for its seat. Its id is also the idempotent reservation id sent to event-service.
     */
    public static Registration request(UUID id, UUID eventId, UUID userId, Instant now) {
        return new Registration(id, eventId, userId, now);
    }

    /**
     * event-service reserved the seat.
     *
     * @return {@code false} when the registration no longer waits for it (cancelled meanwhile, or a reply delivered
     * again): nothing changes
     */
    public boolean confirmSeat() {
        if (status != RegistrationStatus.PENDING) {
            return false;
        }
        status = RegistrationStatus.CONFIRMED;
        return true;
    }

    /**
     * event-service refused the seat.
     *
     * @param reason the code of the refusal, e.g. {@code EVENT_FULL}
     * @return {@code false} when the registration no longer waits for a seat: nothing changes
     */
    public boolean rejectSeat(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (status != RegistrationStatus.PENDING) {
            return false;
        }
        status = RegistrationStatus.REJECTED;
        rejectionReason = reason;
        return true;
    }

    /**
     * Allowed while the seat is requested too: the release then follows the reservation.
     */
    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == RegistrationStatus.CANCELLED) {
            throw new RegistrationAlreadyCancelledException(id);
        }
        if (status == RegistrationStatus.REJECTED) {
            throw new RegistrationRejectedException(id);
        }
        status = RegistrationStatus.CANCELLED;
        cancelledAt = now;
    }

    public boolean isCancelled() {
        return status == RegistrationStatus.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    /** A registration is private to the user it belongs to. */
    public boolean belongsTo(UUID userId) {
        return this.userId.equals(userId);
    }

    public UUID getUserId() {
        return userId;
    }

    public RegistrationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getVersion() {
        return version;
    }
}
