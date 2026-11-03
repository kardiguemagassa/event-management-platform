package com.kmg.eventplatform.registration.domain;

import com.kmg.eventplatform.registration.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class RegistrationRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Autowired
    private RegistrationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void persistsAssignedIdAsNewEntity() {
        UUID id = UUID.randomUUID();

        repository.saveAndFlush(Registration.confirm(id, eventId, userId, NOW));

        Registration found = repository.findById(id).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(found.getVersion()).isZero();
    }

    @Test
    void findsByEventAndByUserSortedByCreationDate() {
        UUID otherUser = UUID.randomUUID();
        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), eventId, otherUser, NOW.plusSeconds(10)));
        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), eventId, userId, NOW));
        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), UUID.randomUUID(), userId, NOW.plusSeconds(20)));

        PageRequest byDate = PageRequest.of(0, 10, Sort.by("createdAt"));
        Page<Registration> forEvent = repository.findByEventId(eventId, byDate);
        Page<Registration> forUser = repository.findAll(RegistrationSpecifications.ofUser(userId), byDate);

        assertThat(forEvent.getContent()).extracting(Registration::getUserId).containsExactly(userId, otherUser);
        assertThat(forUser.getTotalElements()).isEqualTo(2);
    }

    @Test
    void findsTheRegistrationsOfAnEventByStatus() {
        Registration cancelled = Registration.confirm(UUID.randomUUID(), eventId, userId, NOW);
        cancelled.cancel(NOW.plusSeconds(5));
        repository.saveAndFlush(cancelled);
        Registration confirmed = repository.saveAndFlush(
                Registration.confirm(UUID.randomUUID(), eventId, UUID.randomUUID(), NOW.plusSeconds(10)));

        PageRequest page = PageRequest.of(0, 10);
        assertThat(repository.findByEventIdAndStatus(eventId, RegistrationStatus.CONFIRMED, page).getContent())
                .extracting(Registration::getId).containsExactly(confirmed.getId());
        assertThat(repository.findByEventIdAndStatus(eventId, RegistrationStatus.CANCELLED, page).getContent())
                .extracting(Registration::getId).containsExactly(cancelled.getId());
    }

    @Test
    void findsTheCallersRegistrationToOneEventInOneStatus() {
        Registration cancelled = Registration.confirm(UUID.randomUUID(), eventId, userId, NOW);
        cancelled.cancel(NOW.plusSeconds(5));
        repository.saveAndFlush(cancelled);
        Registration active = repository.saveAndFlush(
                Registration.confirm(UUID.randomUUID(), eventId, userId, NOW.plusSeconds(10)));
        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), UUID.randomUUID(), userId, NOW));
        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), eventId, UUID.randomUUID(), NOW));

        Specification<Registration> mineToThisEvent = Specification.allOf(
                RegistrationSpecifications.ofUser(userId), RegistrationSpecifications.forEvent(eventId));
        PageRequest page = PageRequest.of(0, 10, Sort.by("createdAt"));

        assertThat(repository.findAll(mineToThisEvent, page).getContent())
                .extracting(Registration::getId).containsExactly(cancelled.getId(), active.getId());
        assertThat(repository.findAll(mineToThisEvent.and(
                RegistrationSpecifications.hasStatus(RegistrationStatus.CONFIRMED)), page).getContent())
                .extracting(Registration::getId).containsExactly(active.getId());
    }

    @Test
    void detectsActiveRegistrationOnly() {
        Registration registration = Registration.confirm(UUID.randomUUID(), eventId, userId, NOW);
        repository.saveAndFlush(registration);
        assertThat(repository.existsByEventIdAndUserIdAndStatus(eventId, userId, RegistrationStatus.CONFIRMED)).isTrue();

        registration.cancel(NOW.plusSeconds(60));
        repository.saveAndFlush(registration);

        assertThat(repository.existsByEventIdAndUserIdAndStatus(eventId, userId, RegistrationStatus.CONFIRMED)).isFalse();
    }

    @Test
    void databaseRejectsSecondActiveRegistration() {
        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), eventId, userId, NOW));

        assertThatThrownBy(() -> repository.saveAndFlush(
                Registration.confirm(UUID.randomUUID(), eventId, userId, NOW.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_registrations_active_event_user");
    }

    @Test
    void userCanRegisterAgainAfterCancelling() {
        Registration first = Registration.confirm(UUID.randomUUID(), eventId, userId, NOW);
        repository.saveAndFlush(first);
        first.cancel(NOW.plusSeconds(60));
        repository.saveAndFlush(first);

        repository.saveAndFlush(Registration.confirm(UUID.randomUUID(), eventId, userId, NOW.plusSeconds(120)));

        assertThat(repository.findByEventId(eventId, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
    }

    @Test
    void databaseRejectsCancelledRowWithoutCancellationDate() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO registrations (id, event_id, user_id, status, created_at) VALUES (?, ?, ?, 'CANCELLED', now())",
                UUID.randomUUID(), eventId, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_registrations_cancelled_at");
    }
}
