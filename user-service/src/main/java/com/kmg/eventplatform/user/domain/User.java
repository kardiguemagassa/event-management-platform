package com.kmg.eventplatform.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A platform user. Instances are always valid: creation goes through {@link #register}
 * and changes through {@link #changeDetails}, which enforce the invariants.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Optimistic locking. Wrapper type so Spring Data treats a null version as a new entity. */
    @Version
    private Long version;

    protected User() {
        // required by JPA
    }

    private User(UUID id, String fullName, String email, Instant createdAt) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.createdAt = createdAt;
    }

    public static User register(String fullName, String email, Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return new User(UUID.randomUUID(), requireFullName(fullName), normalizeEmail(email), now);
    }

    public void changeDetails(String fullName, String email) {
        this.fullName = requireFullName(fullName);
        this.email = normalizeEmail(email);
    }

    /**
     * Canonical form used for storage and uniqueness checks.
     * Locale.ROOT avoids locale-specific case mapping (e.g. the Turkish dotless i).
     */
    public static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireFullName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName must not be blank");
        }
        return fullName.strip();
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof User other && id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
