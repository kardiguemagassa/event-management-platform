package com.kmg.eventplatform.registration.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

/** The caller's registrations are queried with {@link RegistrationSpecifications}. */
public interface RegistrationRepository extends JpaRepository<Registration, UUID>, JpaSpecificationExecutor<Registration> {

    Page<Registration> findByEventId(UUID eventId, Pageable pageable);

    Page<Registration> findByEventIdAndStatus(UUID eventId, RegistrationStatus status, Pageable pageable);

    boolean existsByEventIdAndUserIdAndStatus(UUID eventId, UUID userId, RegistrationStatus status);
}
