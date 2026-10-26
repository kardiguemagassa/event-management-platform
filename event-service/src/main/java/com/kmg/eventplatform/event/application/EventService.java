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
    public Event create(EventDetails details) {
        return repository.save(Event.create(details, clock.instant()));
    }

    public Event getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new EventNotFoundException(id));
    }

    /** @param status optional filter, {@code null} for all events */
    public Page<Event> list(EventStatus status, Pageable pageable) {
        return status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
    }

    @Transactional
    public Event update(UUID id, EventDetails details) {
        Event event = getById(id);
        event.update(details, clock.instant());
        return event;
    }

    @Transactional
    public Event publish(UUID id) {
        Event event = getById(id);
        event.publish(clock.instant());
        return event;
    }

    @Transactional
    public Event cancel(UUID id) {
        Event event = getById(id);
        event.cancel();
        return event;
    }

    @Transactional
    public void delete(UUID id) {
        Event event = getById(id);
        event.ensureDeletable();
        repository.delete(event);
    }
}
