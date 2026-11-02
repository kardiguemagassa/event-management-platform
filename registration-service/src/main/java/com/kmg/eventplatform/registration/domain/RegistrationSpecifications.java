package com.kmg.eventplatform.registration.domain;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** Composable criteria of the caller's registrations; the application layer combines them. */
public final class RegistrationSpecifications {

    private RegistrationSpecifications() {
    }

    public static Specification<Registration> ofUser(UUID userId) {
        return (root, query, cb) -> cb.equal(root.get("userId"), userId);
    }

    public static Specification<Registration> forEvent(UUID eventId) {
        return (root, query, cb) -> cb.equal(root.get("eventId"), eventId);
    }

    public static Specification<Registration> hasStatus(RegistrationStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
