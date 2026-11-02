package com.kmg.eventplatform.event.application;

import static com.kmg.eventplatform.event.domain.EventSpecifications.hasStatus;
import static com.kmg.eventplatform.event.domain.EventSpecifications.idIn;
import static com.kmg.eventplatform.event.domain.EventSpecifications.organizedBy;
import static com.kmg.eventplatform.event.domain.EventSpecifications.startsAtOrAfter;
import static com.kmg.eventplatform.event.domain.EventSpecifications.titleOrLocationContains;
import static com.kmg.eventplatform.event.domain.EventSpecifications.visibleTo;

import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
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
     * Events matching the search, among those visible to the viewer: every published or cancelled event, plus the
     * viewer's own drafts. With {@link EventSearch#mine()}, every event of the viewer and only those.
     *
     * @param viewerId the caller, {@code null} when anonymous (never with {@code mine})
     */
    public Page<Event> list(EventSearch search, UUID viewerId, Pageable pageable) {
        List<Specification<Event>> criteria = new ArrayList<>();
        if (search.mine()) {
            criteria.add(organizedBy(Objects.requireNonNull(viewerId, "mine requires an authenticated caller")));
        } else {
            criteria.add(visibleTo(viewerId));
        }
        if (search.status() != null) {
            criteria.add(hasStatus(search.status()));
        }
        if (search.text() != null) {
            criteria.add(titleOrLocationContains(search.text()));
        }
        if (search.from() != null) {
            criteria.add(startsAtOrAfter(search.from()));
        }
        if (search.ids() != null) {
            criteria.add(idIn(search.ids()));
        }
        return repository.findAll(Specification.allOf(criteria), pageable);
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
