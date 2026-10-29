package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Mock
    private RegistrationRepository repository;

    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizerId = UUID.randomUUID();
    private final FakeEventCatalog events = new FakeEventCatalog();
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        events.events.add(eventId);
        events.organizers.put(eventId, organizerId);
        service = new RegistrationService(repository, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void registerHoldsSeatThenConfirms() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Registration registration = service.register(eventId, userId);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.getCreatedAt()).isEqualTo(NOW);
        assertThat(events.heldSeats).containsEntry(registration.getId(), eventId);
    }

    @Test
    void unknownEventIsRejectedBeforeAnySideEffect() {
        assertThatThrownBy(() -> service.register(UUID.randomUUID(), userId))
                .isInstanceOf(EventNotFoundException.class);

        assertThat(events.heldSeats).isEmpty();
    }

    @Test
    void businessRejectionNeedsNoCompensation() {
        events.reserveRejection = new EventFullException(eventId);

        assertThatThrownBy(() -> service.register(eventId, userId)).isInstanceOf(EventFullException.class);

        assertThat(events.releaseCalls).isZero();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateIsRejectedBeforeAnySideEffect() {
        when(repository.existsByEventIdAndUserIdAndStatus(eventId, userId, RegistrationStatus.CONFIRMED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DuplicateRegistrationException.class);

        assertThat(events.heldSeats).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateLosingTheRaceReleasesItsSeat() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new SQLException("duplicate key value violates unique constraint \""
                        + RegistrationService.ACTIVE_REGISTRATION_INDEX + "\"")));

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DuplicateRegistrationException.class);

        assertThat(events.heldSeats).isEmpty();
    }

    @Test
    void otherIntegrityViolationsAreNotMistakenForDuplicates() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new SQLException("new row violates check constraint \"ck_registrations_status\"")));

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(events.heldSeats).isEmpty();
    }

    @Test
    void reservationWithUnknownOutcomeIsCompensated() {
        events.reserveTimesOutAfterCommit = true;

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DownstreamUnavailableException.class);

        assertThat(events.heldSeats).isEmpty();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void localSaveFailureReleasesTheSeat() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(events.heldSeats).isEmpty();
    }

    @Test
    void failedCompensationNeverHidesTheOriginalFailure() {
        when(repository.saveAndFlush(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        DownstreamUnavailableException releaseFailure = new DownstreamUnavailableException("event-service", "down");
        events.releaseFailure = releaseFailure;

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasSuppressedException(releaseFailure);
    }

    @Test
    void cancelReleasesSeatThenCancels() {
        Registration registration = confirmedRegistration();
        events.heldSeats.put(registration.getId(), eventId);
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));
        when(repository.saveAndFlush(registration)).thenReturn(registration);

        Registration cancelled = service.cancel(registration.getId(), userId);

        assertThat(cancelled.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW);
        assertThat(events.heldSeats).isEmpty();
    }

    @Test
    void cancelKeepsRegistrationConfirmedWhenSeatCannotBeReleased() {
        Registration registration = confirmedRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));
        events.releaseFailure = new DownstreamUnavailableException("event-service", "down");

        assertThatThrownBy(() -> service.cancel(registration.getId(), userId))
                .isInstanceOf(DownstreamUnavailableException.class);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void cancelOfCancelledRegistrationFailsWithoutRemoteCall() {
        Registration registration = confirmedRegistration();
        registration.cancel(NOW);
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.cancel(registration.getId(), userId))
                .isInstanceOf(RegistrationAlreadyCancelledException.class);

        assertThat(events.releaseCalls).isZero();
    }

    @Test
    void unknownRegistrationIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(unknown, userId)).isInstanceOf(RegistrationNotFoundException.class);
    }

    @Test
    void registrationOfAnotherUserDoesNotExistForTheCaller() {
        Registration registration = confirmedRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));
        UUID otherUser = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(registration.getId(), otherUser))
                .isInstanceOf(RegistrationNotFoundException.class);
        assertThatThrownBy(() -> service.cancel(registration.getId(), otherUser))
                .isInstanceOf(RegistrationNotFoundException.class);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(events.releaseCalls).isZero();
    }

    @Test
    void attendeeReadsOwnRegistration() {
        Registration registration = confirmedRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        assertThat(service.get(registration.getId(), userId)).isSameAs(registration);
    }

    @Test
    void organizerListsTheRegistrationsOfTheirEvent() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Registration> page = new PageImpl<>(List.of(confirmedRegistration()));
        when(repository.findByEventId(eventId, pageable)).thenReturn(page);

        assertThat(service.findByEvent(eventId, organizerId, pageable)).isSameAs(page);
    }

    @Test
    void otherUsersCannotListTheRegistrationsOfAnEvent() {
        assertThatThrownBy(() -> service.findByEvent(eventId, userId, PageRequest.of(0, 20)))
                .isInstanceOf(NotEventOrganizerException.class)
                .hasMessageContaining(eventId.toString());
        verify(repository, never()).findByEventId(any(), any());
    }

    @Test
    void registrationsOfAnEventCreatedBeforeOwnershipAreListedByNobody() {
        events.organizers.remove(eventId);

        assertThatThrownBy(() -> service.findByEvent(eventId, organizerId, PageRequest.of(0, 20)))
                .isInstanceOf(NotEventOrganizerException.class);
    }

    @Test
    void attendeeListsOwnRegistrations() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Registration> page = new PageImpl<>(List.of(confirmedRegistration()));
        when(repository.findByUserId(userId, pageable)).thenReturn(page);

        assertThat(service.findByUser(userId, pageable)).isSameAs(page);
    }

    private Registration confirmedRegistration() {
        return Registration.confirm(UUID.randomUUID(), eventId, userId, NOW.minusSeconds(3600));
    }
}
