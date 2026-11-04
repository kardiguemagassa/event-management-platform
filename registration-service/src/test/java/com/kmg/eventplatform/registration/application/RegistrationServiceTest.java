package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.Registrations;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Set<RegistrationStatus> ACTIVE = Set.of(RegistrationStatus.PENDING, RegistrationStatus.CONFIRMED);

    @Mock
    private RegistrationRepository repository;

    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID organizerId = UUID.randomUUID();
    private final FakeEventCatalog events = new FakeEventCatalog();
    private final FakeSeatRequests seats = new FakeSeatRequests();
    private final FakeAttendeeDirectory attendees = new FakeAttendeeDirectory();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        events.events.add(eventId);
        events.organizers.put(eventId, organizerId);
        service = new RegistrationService(repository, events, seats, Clock.fixed(NOW, ZoneOffset.UTC),
                new RegistrationMetrics(meters), attendees);
    }

    @Test
    void registeringRequestsTheSeatOfAPendingRegistration() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Registration registration = service.register(eventId, userId);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(registration.getCreatedAt()).isEqualTo(NOW);
        assertThat(registration.getUserId()).isEqualTo(userId);
        assertThat(seats.sent).containsExactly(
                new FakeSeatRequests.Request("reserve", eventId, registration.getId()));
        assertThat(meters.counter(RegistrationMetrics.CONFIRMED).count()).as("not before the answer").isZero();
    }

    @Test
    void duplicateIsRejectedBeforeAnySeatRequest() {
        when(repository.existsByEventIdAndUserIdAndStatusIn(eventId, userId, ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DuplicateRegistrationException.class);

        assertThat(seats.sent).isEmpty();
        verify(repository, never()).saveAndFlush(any());
        assertThat(rejected("ALREADY_REGISTERED")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateLosingTheRaceRequestsNoSeat() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new SQLException("duplicate key value violates unique constraint \""
                        + RegistrationService.ACTIVE_REGISTRATION_INDEX + "\"")));

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DuplicateRegistrationException.class);

        assertThat(seats.sent).isEmpty();
        assertThat(rejected("ALREADY_REGISTERED")).isEqualTo(1);
    }

    @Test
    void otherIntegrityViolationsAreNotMistakenForDuplicates() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("could not execute",
                new SQLException("new row violates check constraint \"ck_registrations_status\"")));

        assertThatThrownBy(() -> service.register(eventId, userId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(seats.sent).isEmpty();
    }

    @Test
    void aReservedSeatConfirmsTheRegistrationAndIsCounted() {
        Registration registration = pendingRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        service.seatReserved(registration.getId());
        service.seatReserved(registration.getId());

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(meters.counter(RegistrationMetrics.CONFIRMED).count()).as("counted once").isEqualTo(1);
    }

    @Test
    void aRefusedSeatRejectsTheRegistrationAndIsCountedWithItsReason() {
        Registration registration = pendingRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        service.seatRejected(registration.getId(), "EVENT_FULL");
        service.seatRejected(registration.getId(), "EVENT_FULL");

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.REJECTED);
        assertThat(registration.getRejectionReason()).isEqualTo("EVENT_FULL");
        assertThat(rejected("EVENT_FULL")).as("counted once").isEqualTo(1);
    }

    @Test
    void answersAboutAnUnknownRegistrationAreIgnored() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        service.seatReserved(unknown);
        service.seatRejected(unknown, "EVENT_FULL");

        assertThat(meters.counter(RegistrationMetrics.CONFIRMED).count()).isZero();
        assertThat(rejected("EVENT_FULL")).isZero();
    }

    @Test
    void cancellingWhileTheSeatIsRequestedReleasesItAfterTheReservation() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Registration registration = service.register(eventId, userId);
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        Registration cancelled = service.cancel(registration.getId(), userId);
        service.seatReserved(registration.getId());

        assertThat(cancelled.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isEqualTo(NOW);
        assertThat(seats.sent).extracting(FakeSeatRequests.Request::type).containsExactly("reserve", "release");
        assertThat(meters.counter(RegistrationMetrics.CANCELLED).count()).isEqualTo(1);
        assertThat(meters.counter(RegistrationMetrics.CONFIRMED).count()).isZero();
    }

    @Test
    void cancellingAConfirmedRegistrationReleasesItsSeat() {
        Registration registration = confirmedRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));
        when(repository.saveAndFlush(registration)).thenReturn(registration);

        service.cancel(registration.getId(), userId);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(seats.sent).containsExactly(
                new FakeSeatRequests.Request("release", eventId, registration.getId()));
    }

    @Test
    void cancelOfCancelledRegistrationFailsWithoutSeatRequest() {
        Registration registration = confirmedRegistration();
        registration.cancel(NOW);
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service.cancel(registration.getId(), userId))
                .isInstanceOf(RegistrationAlreadyCancelledException.class);

        assertThat(seats.sent).isEmpty();
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
        assertThat(seats.sent).isEmpty();
    }

    @Test
    void attendeeReadsOwnRegistration() {
        Registration registration = confirmedRegistration();
        when(repository.findById(registration.getId())).thenReturn(Optional.of(registration));

        assertThat(service.get(registration.getId(), userId)).isSameAs(registration);
    }

    @Test
    void organizerListsTheRegistrationsOfTheirEventWithTheAttendeeNames() {
        Pageable pageable = PageRequest.of(0, 20);
        Registration withProfile = confirmedRegistration();
        Registration withoutProfile = Registrations.confirmed(UUID.randomUUID(), eventId, UUID.randomUUID(), NOW);
        attendees.names.put(userId, "Ada Lovelace");
        when(repository.findByEventId(eventId, pageable))
                .thenReturn(new PageImpl<>(List.of(withProfile, withoutProfile), pageable, 2));

        Page<EventRegistration> page = service.findByEvent(eventId, null, organizerId, pageable);

        assertThat(page.getContent()).containsExactly(
                new EventRegistration(withProfile, "Ada Lovelace"),
                new EventRegistration(withoutProfile, null));
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(attendees.calls).as("one call per page").isEqualTo(1);
    }

    @Test
    void organizerFiltersTheRegistrationsByStatus() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByEventIdAndStatus(eventId, RegistrationStatus.CANCELLED, pageable))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(service.findByEvent(eventId, RegistrationStatus.CANCELLED, organizerId, pageable)).isEmpty();
        assertThat(attendees.calls).as("no attendee, no call").isZero();
    }

    @Test
    void registrationsAreListedWithoutNamesWhenUserServiceFails() {
        Pageable pageable = PageRequest.of(0, 20);
        Registration registration = confirmedRegistration();
        attendees.failure = new DownstreamUnavailableException("user-service", "circuit breaker is open");
        when(repository.findByEventId(eventId, pageable)).thenReturn(new PageImpl<>(List.of(registration), pageable, 1));

        assertThat(service.findByEvent(eventId, null, organizerId, pageable).getContent())
                .containsExactly(new EventRegistration(registration, null));
    }

    @Test
    void otherUsersCannotListTheRegistrationsOfAnEvent() {
        assertThatThrownBy(() -> service.findByEvent(eventId, null, userId, PageRequest.of(0, 20)))
                .isInstanceOf(NotEventOrganizerException.class)
                .hasMessageContaining(eventId.toString());
        verify(repository, never()).findByEventId(any(), any());
        assertThat(attendees.calls).isZero();
    }

    @Test
    void administratorListsTheRegistrationsOfAnyEventWithTheAttendeeNames() {
        Pageable pageable = PageRequest.of(0, 20);
        Registration registration = confirmedRegistration();
        attendees.names.put(userId, "Ada Lovelace");
        when(repository.findByEventIdAndStatus(eventId, RegistrationStatus.CONFIRMED, pageable))
                .thenReturn(new PageImpl<>(List.of(registration), pageable, 1));

        Page<EventRegistration> page = service.findByEventAsAdministrator(
                eventId, RegistrationStatus.CONFIRMED, UUID.randomUUID(), pageable);

        assertThat(page.getContent()).containsExactly(new EventRegistration(registration, "Ada Lovelace"));
    }

    @Test
    void registrationsOfAnEventCreatedBeforeOwnershipAreListedByNobody() {
        events.organizers.remove(eventId);

        assertThatThrownBy(() -> service.findByEvent(eventId, null, organizerId, PageRequest.of(0, 20)))
                .isInstanceOf(NotEventOrganizerException.class);
    }

    @Test
    void attendeeListsOwnRegistrations() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Registration> page = new PageImpl<>(List.of(confirmedRegistration()));
        when(repository.findAll(ArgumentMatchers.<Specification<Registration>>any(), eq(pageable))).thenReturn(page);

        assertThat(service.findByUser(userId, null, null, pageable)).isSameAs(page);
    }

    @Test
    void attendeeChecksTheirRegistrationToOneEvent() {
        Pageable pageable = PageRequest.of(0, 1);
        Page<Registration> page = new PageImpl<>(List.of(confirmedRegistration()));
        when(repository.findAll(ArgumentMatchers.<Specification<Registration>>any(), eq(pageable))).thenReturn(page);

        assertThat(service.findByUser(userId, eventId, RegistrationStatus.CONFIRMED, pageable)).isSameAs(page);
    }

    private Registration pendingRegistration() {
        return Registration.request(UUID.randomUUID(), eventId, userId, NOW.minusSeconds(1));
    }

    private Registration confirmedRegistration() {
        return Registrations.confirmed(UUID.randomUUID(), eventId, userId, NOW.minusSeconds(3600));
    }

    private double rejected(String reason) {
        return meters.counter(RegistrationMetrics.REJECTED, RegistrationMetrics.REASON, reason).count();
    }
}
