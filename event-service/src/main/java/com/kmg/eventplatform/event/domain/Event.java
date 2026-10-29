package com.kmg.eventplatform.event.domain;

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

/**
 * An event with a lifecycle (DRAFT -> PUBLISHED -> CANCELLED) and a capacity.
 * All state changes go through methods that enforce the business rules.
 */
@Entity
@Table(name = "events")
public class Event {

    @Id
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "location", nullable = false, length = 200)
    private String location;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "capacity", nullable = false)
    private int capacity;

    /**
     * Read-only for Hibernate: only changed by atomic SQL in the capacity reservation flow.
     * Otherwise any entity update would rewrite a possibly stale value (lost update).
     */
    @Column(name = "reserved_seats", nullable = false, insertable = false, updatable = false)
    private int reservedSeats;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Keycloak id ({@code sub}) of the user who created the event; null for events created before ownership. */
    @Column(name = "organizer_id", updatable = false)
    private UUID organizerId;

    @Version
    private Long version;

    protected Event() {
        // required by JPA
    }

    public static Event create(EventDetails details, UUID organizerId, Instant now) {
        Objects.requireNonNull(details, "details must not be null");
        Objects.requireNonNull(organizerId, "organizerId must not be null");
        requireFutureStart(null, details.startAt(), now);
        Event event = new Event();
        event.id = UUID.randomUUID();
        event.applyDetails(details);
        event.status = EventStatus.DRAFT;
        event.createdAt = now;
        event.organizerId = organizerId;
        return event;
    }

    /** Only its organizer may change an event. */
    public boolean isOrganizedBy(UUID userId) {
        return organizerId != null && organizerId.equals(userId);
    }

    /** A draft is private to its organizer; published and cancelled events are public. */
    public boolean isVisibleTo(UUID viewerId) {
        return status != EventStatus.DRAFT || isOrganizedBy(viewerId);
    }

    public void update(EventDetails details, Instant now) {
        Objects.requireNonNull(details, "details must not be null");
        if (status == EventStatus.CANCELLED) {
            throw new InvalidEventStateException(id, status, "update");
        }
        requireFutureStart(id, details.startAt(), now);
        if (details.capacity() < reservedSeats) {
            throw new CapacityBelowReservedException(id, details.capacity(), reservedSeats);
        }
        applyDetails(details);
    }

    /** Idempotent: publishing an already published event is a no-op. */
    public void publish(Instant now) {
        switch (status) {
            case PUBLISHED -> {
                // already published
            }
            case CANCELLED -> throw new InvalidEventStateException(id, status, "publish");
            case DRAFT -> {
                requireFutureStart(id, startAt, now);
                status = EventStatus.PUBLISHED;
            }
        }
    }

    /** Idempotent: cancelling an already cancelled event is a no-op. */
    public void cancel() {
        status = EventStatus.CANCELLED;
    }

    public void ensureDeletable() {
        if (reservedSeats > 0) {
            throw new EventHasReservationsException(id, reservedSeats);
        }
    }

    /** Registrations are only accepted for published events that have not started yet. */
    public boolean isRegistrable(Instant now) {
        return status == EventStatus.PUBLISHED && startAt.isAfter(now);
    }

    public int availableSeats() {
        return capacity - reservedSeats;
    }

    private void applyDetails(EventDetails details) {
        this.title = details.title();
        this.description = details.description();
        this.location = details.location();
        this.startAt = details.startAt();
        this.capacity = details.capacity();
    }

    private static void requireFutureStart(UUID eventId, Instant startAt, Instant now) {
        if (!startAt.isAfter(now)) {
            throw new EventStartNotInFutureException(eventId, startAt);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getLocation() {
        return location;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getReservedSeats() {
        return reservedSeats;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getOrganizerId() {
        return organizerId;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Event other && id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
