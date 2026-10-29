package com.kmg.eventplatform.event.application;

import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventRepository;
import com.kmg.eventplatform.event.domain.EventStatus;
import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event use cases. Orchestration only: every business rule lives in {@link Event}.
 */
@Service
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository repository;
    private final Clock clock;

    public EventService(EventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Event create(EventDetails details, UUID organizerId) {
        return repository.save(Event.create(details, organizerId, clock.instant()));
    }

    public Event getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }

    /**
     * The drafts of other organizers do not exist for the viewer: 404, like an unknown id.
     *
     * @param viewerId the caller, {@code null} when anonymous
     */
    public Event getVisible(UUID id, UUID viewerId) {
        Event event = getById(id);
        if (!event.isVisibleTo(viewerId)) {
            throw new EventNotFoundException(id);
        }
        return event;
    }

    /**
     * Events visible to the viewer: every published or cancelled event, plus the viewer's own drafts.
     *
     * @param status   optional filter, {@code null} for all visible events
     * @param viewerId the caller, {@code null} when anonymous
     */
    public Page<Event> list(EventStatus status, UUID viewerId, Pageable pageable) {
        if (status == null) {
            return viewerId == null
                    ? repository.findByStatusNot(EventStatus.DRAFT, pageable)
                    : repository.findVisibleTo(viewerId, EventStatus.DRAFT, pageable);
        }
        if (status == EventStatus.DRAFT) {
            return viewerId == null
                    ? Page.empty(pageable)
                    : repository.findByStatusAndOrganizerId(EventStatus.DRAFT, viewerId, pageable);
        }
        return repository.findByStatus(status, pageable);
    }

    @Transactional
    public Event update(UUID id, EventDetails details, UUID organizerId) {
        Event event = getOwned(id, organizerId);
        event.update(details, clock.instant());
        return event;
    }

    @Transactional
    public Event publish(UUID id, UUID organizerId) {
        Event event = getOwned(id, organizerId);
        event.publish(clock.instant());
        return event;
    }

    @Transactional
    public Event cancel(UUID id, UUID organizerId) {
        Event event = getOwned(id, organizerId);
        event.cancel();
        return event;
    }

    @Transactional
    public void delete(UUID id, UUID organizerId) {
        Event event = getOwned(id, organizerId);
        event.ensureDeletable();
        repository.delete(event);
    }

    /** Object-level authorization: the role allows the action, ownership allows it on this event. */
    private Event getOwned(UUID id, UUID organizerId) {
        Event event = getVisible(id, organizerId);
        if (!event.isOrganizedBy(organizerId)) {
            throw new NotEventOrganizerException(id);
        }
        return event;
    }
}
