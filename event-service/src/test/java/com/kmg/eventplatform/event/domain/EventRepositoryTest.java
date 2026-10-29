package com.kmg.eventplatform.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kmg.eventplatform.event.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest(showSql = false)
@Import(TestcontainersConfiguration.class)
class EventRepositoryTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final Instant NEXT_MONTH = NOW.plus(Duration.ofDays(30));
    private static final UUID ORGANIZER = UUID.randomUUID();

    @Autowired
    private EventRepository repository;

    @Autowired
    private EntityManager entityManager;

    private Event saveEvent(int capacity) {
        return saveEvent(capacity, ORGANIZER);
    }

    private Event saveEvent(int capacity, UUID organizer) {
        EventDetails details = new EventDetails("Spring Meetup", "Talks", "Paris", NEXT_MONTH, capacity);
        return repository.saveAndFlush(Event.create(details, organizer, NOW));
    }

    private void setReservedSeatsInDatabase(Event event, int seats) {
        entityManager.createNativeQuery("UPDATE events SET reserved_seats = ?1 WHERE id = ?2")
                .setParameter(1, seats)
                .setParameter(2, event.getId())
                .executeUpdate();
    }

    @Test
    void persistsAndReloadsAllFields() {
        Event saved = saveEvent(50);
        entityManager.clear();

        Event reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getTitle()).isEqualTo("Spring Meetup");
        assertThat(reloaded.getStartAt()).isEqualTo(NEXT_MONTH);
        assertThat(reloaded.getCapacity()).isEqualTo(50);
        assertThat(reloaded.getReservedSeats()).isZero();
        assertThat(reloaded.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(reloaded.getVersion()).isZero();
        assertThat(reloaded.getOrganizerId()).isEqualTo(ORGANIZER);
    }

    @Test
    void visibleEventsExcludeTheDraftsOfOtherOrganizers() {
        UUID viewer = UUID.randomUUID();
        Event ownDraft = saveEvent(10, viewer);
        Event otherDraft = saveEvent(10, UUID.randomUUID());
        Event published = saveEvent(10, UUID.randomUUID());
        published.publish(NOW);
        repository.saveAndFlush(published);

        var visible = repository.findVisibleTo(viewer, EventStatus.DRAFT, PageRequest.of(0, 100, Sort.by("startAt")));
        var anonymous = repository.findByStatusNot(EventStatus.DRAFT, PageRequest.of(0, 100));
        var ownDrafts = repository.findByStatusAndOrganizerId(EventStatus.DRAFT, viewer, PageRequest.of(0, 100));

        assertThat(visible.getContent()).extracting(Event::getId)
                .contains(ownDraft.getId(), published.getId())
                .doesNotContain(otherDraft.getId());
        assertThat(anonymous.getContent()).extracting(Event::getId)
                .contains(published.getId())
                .doesNotContain(ownDraft.getId(), otherDraft.getId());
        assertThat(ownDrafts.getContent()).extracting(Event::getId).containsExactly(ownDraft.getId());
    }

    @Test
    void findsEventsByStatus() {
        Event draft = saveEvent(10);
        Event published = saveEvent(10);
        published.publish(NOW);
        repository.saveAndFlush(published);

        var page = repository.findByStatus(EventStatus.PUBLISHED, PageRequest.of(0, 10, Sort.by("startAt")));

        assertThat(page.getContent()).extracting(Event::getId)
                .contains(published.getId())
                .doesNotContain(draft.getId());
    }

    @Test
    void entityUpdateNeverOverwritesReservedSeats() {
        Event event = saveEvent(10);
        setReservedSeatsInDatabase(event, 2);

        event.update(new EventDetails("Renamed", null, "Paris", NEXT_MONTH, 10), NOW);
        repository.saveAndFlush(event);
        entityManager.clear();

        Event reloaded = repository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Renamed");
        assertThat(reloaded.getReservedSeats()).isEqualTo(2);
    }

    @Test
    void databaseRejectsReservedSeatsAboveCapacity() {
        Event event = saveEvent(2);

        assertThatThrownBy(() -> setReservedSeatsInDatabase(event, 3))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ck_events_reserved_seats_range");
    }

    @Test
    void databaseRejectsCapacityBelowReservedSeats() {
        Event event = saveEvent(10);
        setReservedSeatsInDatabase(event, 5);

        assertThatThrownBy(() -> entityManager.createNativeQuery("UPDATE events SET capacity = 4 WHERE id = ?1")
                        .setParameter(1, event.getId())
                        .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ck_events_reserved_seats_range");
    }
}
