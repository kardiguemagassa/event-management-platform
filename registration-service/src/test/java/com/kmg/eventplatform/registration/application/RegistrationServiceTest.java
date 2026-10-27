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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
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
    private final FakeEventCatalog events = new FakeEventCatalog();
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        events.events.add(eventId);
        UserDirectory users = Set.of(userId)::contains;
        service = new RegistrationService(repository, users, events, Clock.fixed(NOW, ZoneOffset.UTC));
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
    void unknownUserIsRejectedBeforeAnySideEffect() {
        assertThatThrownBy(() -> service.register(eventId, UUID.randomUUID()))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(events.heldSeats).isEmpty();
        verify(repository, never()).saveAndFlush(any());
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

        Registration cancelled = service.cancel(registration.getId());

        assertThat(cancelled.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW);
        assertThat(events.heldSeats).isEmpty();
    }

    @Test
    void cancelKeepsRegistrationConfirmedWhenSeatCannotBeReleased() {
        Registration registration = confirmedRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));
        events.releaseFailure = new DownstreamUnavailableException("event-service", "down");

        assertThatThrownBy(() -> service.cancel(registration.getId()))
                .isInstanceOf(DownstreamUnavailableException.class);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void cancelOfCancelledRegistrationFailsWithoutRemoteCall() {
        Registration registration = confirmedRegistration();
        registration.cancel(NOW);
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.cancel(registration.getId()))
                .isInstanceOf(RegistrationAlreadyCancelledException.class);

        assertThat(events.releaseCalls).isZero();
    }

    @Test
    void unknownRegistrationIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(unknown)).isInstanceOf(RegistrationNotFoundException.class);
    }

    private Registration confirmedRegistration() {
        return Registration.confirm(UUID.randomUUID(), eventId, userId, NOW.minusSeconds(3600));
    }
}
