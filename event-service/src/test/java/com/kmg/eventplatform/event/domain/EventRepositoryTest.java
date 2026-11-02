package com.kmg.eventplatform.event.domain;

import static com.kmg.eventplatform.event.domain.EventSpecifications.hasStatus;
import static com.kmg.eventplatform.event.domain.EventSpecifications.idIn;
import static com.kmg.eventplatform.event.domain.EventSpecifications.organizedBy;
import static com.kmg.eventplatform.event.domain.EventSpecifications.startsAtOrAfter;
import static com.kmg.eventplatform.event.domain.EventSpecifications.titleOrLocationContains;
import static com.kmg.eventplatform.event.domain.EventSpecifications.visibleTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kmg.eventplatform.event.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

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

    private Event savePublished(String title, String location, Instant startAt, UUID organizer) {
        Event event = Event.create(new EventDetails(title, null, location, startAt, 10), organizer, NOW);
        event.publish(NOW);
        return repository.saveAndFlush(event);
    }

    private List<UUID> ids(Specification<Event> criteria) {
        return repository.findAll(criteria, PageRequest.of(0, 100, Sort.by("startAt"))).map(Event::getId).getContent();
    }

    @Test
    void visibleEventsExcludeTheDraftsOfOtherOrganizers() {
        UUID viewer = UUID.randomUUID();
        Event ownDraft = saveEvent(10, viewer);
        Event otherDraft = saveEvent(10, UUID.randomUUID());
        Event published = savePublished("Spring Meetup", "Paris", NEXT_MONTH, UUID.randomUUID());

        assertThat(ids(visibleTo(viewer)))
                .contains(ownDraft.getId(), published.getId())
                .doesNotContain(otherDraft.getId());
        assertThat(ids(visibleTo(null)))
                .contains(published.getId())
                .doesNotContain(ownDraft.getId(), otherDraft.getId());
        assertThat(ids(Specification.allOf(visibleTo(viewer), hasStatus(EventStatus.DRAFT))))
                .containsExactly(ownDraft.getId());
    }

    @Test
    void organizerSeesEveryOwnEventWhateverItsStatus() {
        UUID organizer = UUID.randomUUID();
        Event draft = saveEvent(10, organizer);
        Event cancelled = savePublished("Spring Meetup", "Paris", NEXT_MONTH, organizer);
        cancelled.cancel();
        repository.saveAndFlush(cancelled);
        saveEvent(10, UUID.randomUUID());

        assertThat(ids(organizedBy(organizer))).containsExactlyInAnyOrder(draft.getId(), cancelled.getId());
    }

    @Test
    void textMatchesTitleOrLocationIgnoringCase() {
        UUID organizer = UUID.randomUUID();
        Event byTitle = savePublished("Kafka en pratique", "Lyon", NEXT_MONTH, organizer);
        Event byLocation = savePublished("Spring Day", "Paris, Station F", NEXT_MONTH, organizer);
        savePublished("Angular", "Nantes", NEXT_MONTH, organizer);

        assertThat(ids(Specification.allOf(organizedBy(organizer), titleOrLocationContains("KAFKA"))))
                .containsExactly(byTitle.getId());
        assertThat(ids(Specification.allOf(organizedBy(organizer), titleOrLocationContains("station"))))
                .containsExactly(byLocation.getId());
    }

    @Test
    void likeWildcardsInTheTextMatchLiterally() {
        UUID organizer = UUID.randomUUID();
        Event discount = savePublished("Promo 100% gratuite", "Paris", NEXT_MONTH, organizer);
        savePublished("Promo 100 places", "Paris", NEXT_MONTH, organizer);

        assertThat(ids(Specification.allOf(organizedBy(organizer), titleOrLocationContains("100%"))))
                .containsExactly(discount.getId());
        assertThat(ids(Specification.allOf(organizedBy(organizer), titleOrLocationContains("_"))))
                .isEmpty();
    }

    @Test
    void fromKeepsEventsStartingAtOrAfterTheInstant() {
        UUID organizer = UUID.randomUUID();
        Instant later = NEXT_MONTH.plus(Duration.ofDays(10));
        savePublished("Soon", "Paris", NEXT_MONTH, organizer);
        Event onTheDot = savePublished("On the dot", "Paris", later, organizer);
        Event after = savePublished("After", "Paris", later.plus(Duration.ofDays(1)), organizer);

        assertThat(ids(Specification.allOf(organizedBy(organizer), startsAtOrAfter(later))))
                .containsExactly(onTheDot.getId(), after.getId());
    }

    @Test
    void batchReadKeepsTheVisibilityRules() {
        Event published = savePublished("Spring Meetup", "Paris", NEXT_MONTH, UUID.randomUUID());
        Event otherDraft = saveEvent(10, UUID.randomUUID());
        saveEvent(10, UUID.randomUUID());

        assertThat(ids(Specification.allOf(visibleTo(null), idIn(Set.of(published.getId(), otherDraft.getId())))))
                .containsExactly(published.getId());
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
