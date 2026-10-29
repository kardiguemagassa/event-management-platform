package com.kmg.eventplatform.event.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Page<Event> findByStatus(EventStatus status, Pageable pageable);

    Page<Event> findByStatusNot(EventStatus status, Pageable pageable);

    Page<Event> findByStatusAndOrganizerId(EventStatus status, UUID organizerId, Pageable pageable);

    /** Every event except the drafts of other organizers. */
    @Query("select e from Event e where e.status <> :draft or e.organizerId = :viewer")
    Page<Event> findVisibleTo(@Param("viewer") UUID viewer, @Param("draft") EventStatus draft, Pageable pageable);
}
