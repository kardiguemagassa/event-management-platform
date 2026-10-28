package com.kmg.eventplatform.registration.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RegistrationRepository extends JpaRepository<Registration, UUID> {

    Page<Registration> findByEventId(UUID eventId, Pageable pageable);

    Page<Registration> findByUserId(UUID userId, Pageable pageable);

    boolean existsByEventIdAndUserIdAndStatus(UUID eventId, UUID userId, RegistrationStatus status);
}
