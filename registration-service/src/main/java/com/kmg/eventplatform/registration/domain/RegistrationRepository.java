package com.kmg.eventplatform.registration.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** The caller's registrations are queried with {@link RegistrationSpecifications}. */
public interface RegistrationRepository extends JpaRepository<Registration, UUID>, JpaSpecificationExecutor<Registration> {

    Page<Registration> findByEventId(UUID eventId, Pageable pageable);

    Page<Registration> findByEventIdAndStatus(UUID eventId, RegistrationStatus status, Pageable pageable);

    boolean existsByEventIdAndUserIdAndStatusIn(UUID eventId, UUID userId, Collection<RegistrationStatus> statuses);

    /** Registrations in a status since before an instant: those still waiting for their seat, for instance. */
    long countByStatusAndCreatedAtBefore(RegistrationStatus status, Instant before);
}
