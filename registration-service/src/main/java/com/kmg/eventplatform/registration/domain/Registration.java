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
        this.status = RegistrationStatus.CONFIRMED;
    }

    /**
     * The id is assigned by the caller because it is first sent to event-service
     * as the idempotent reservation id, before this row exists.
     */
    public static Registration confirm(UUID id, UUID eventId, UUID userId, Instant now) {
        return new Registration(id, eventId, userId, now);
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == RegistrationStatus.CANCELLED) {
            throw new RegistrationAlreadyCancelledException(id);
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

    public Long getVersion() {
        return version;
    }
}
