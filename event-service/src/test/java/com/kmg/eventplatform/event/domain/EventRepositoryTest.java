package com.kmg.eventplatform.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kmg.eventplatform.event.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    @Autowired
    private EventRepository repository;

    @Autowired
    private EntityManager entityManager;

    private Event saveEvent(int capacity) {
        EventDetails details = new EventDetails("Spring Meetup", "Talks", "Paris", NEXT_MONTH, capacity);
        return repository.saveAndFlush(Event.create(details, NOW));
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
