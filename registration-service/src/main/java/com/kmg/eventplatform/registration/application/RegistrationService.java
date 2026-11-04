package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.kmg.eventplatform.registration.domain.RegistrationSpecifications.forEvent;
import static com.kmg.eventplatform.registration.domain.RegistrationSpecifications.hasStatus;
import static com.kmg.eventplatform.registration.domain.RegistrationSpecifications.ofUser;

/**
 * Registers the authenticated user to an event of event-service. The attendee is the caller identified by
 * the access token (never an id sent in the request), and a registration is private to its attendee.
 * <p>
 * The seat is reserved asynchronously (saga over Kafka): registering writes a PENDING registration and the seat
 * request in one local transaction, and event-service's answer confirms or rejects it later. No remote call is made
 * while registering or cancelling: both work while event-service is down, and nothing is lost if this service stops.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /** Partial unique index of migrations V2 and V3. */
    static final String ACTIVE_REGISTRATION_INDEX = "uq_registrations_active_event_user";
    private static final Set<RegistrationStatus> ACTIVE =
            Set.of(RegistrationStatus.PENDING, RegistrationStatus.CONFIRMED);
    private static final String ALREADY_REGISTERED = "ALREADY_REGISTERED";

    private final RegistrationRepository repository;
    private final EventCatalog events;
    private final SeatRequests seats;
    private final Clock clock;
    private final RegistrationMetrics metrics;
    private final AttendeeDirectory attendeeDirectory;

    public RegistrationService(RegistrationRepository repository, EventCatalog events, SeatRequests seats, Clock clock,
                               RegistrationMetrics metrics, AttendeeDirectory attendeeDirectory) {
        this.repository = repository;
        this.events = events;
        this.seats = seats;
        this.clock = clock;
        this.metrics = metrics;
        this.attendeeDirectory = attendeeDirectory;
    }

    /**
     * Requests a seat: the registration is PENDING until event-service answers. An unknown, closed or full event is
     * known only then, as a REJECTED registration with its reason.
     *
     * @param userId the authenticated attendee, from the access token
     * @throws DuplicateRegistrationException if the caller already has an active registration to this event
     */
    @Transactional
    public Registration register(UUID eventId, UUID userId) {
        // Fast path with a clear error. Not a guarantee under concurrency: the unique index is, and is translated below.
        if (repository.existsByEventIdAndUserIdAndStatusIn(eventId, userId, ACTIVE)) {
            throw duplicate(eventId, userId);
        }
        Registration requested;
        try {
            requested = repository.saveAndFlush(Registration.request(UUID.randomUUID(), eventId, userId, clock.instant()));
        } catch (DataIntegrityViolationException saveFailure) {
            if (isActiveRegistrationConflict(saveFailure)) {
                throw duplicate(eventId, userId);
            }
            throw saveFailure;
        }
        seats.reserve(eventId, requested.getId());
        // Ids only, no personal data: the audit trail of the saga, correlated with its trace in Grafana.
        log.info("Registration {} requested on event {}", requested.getId(), eventId);
        return requested;
    }

    /**
     * The registration is cancelled at once; its seat is released asynchronously, after the reservation if that is
     * still on its way.
     */
    @Transactional
    public Registration cancel(UUID registrationId, UUID userId) {
        Registration registration = get(registrationId, userId);
        registration.cancel(clock.instant());
        Registration cancelled = repository.saveAndFlush(registration);
        seats.release(cancelled.getEventId(), cancelled.getId());
        metrics.cancelled();
        log.info("Registration {} cancelled on event {}", registrationId, cancelled.getEventId());
        return cancelled;
    }

    /**
     * Answer of event-service: the seat is held. Ignored when the registration no longer waits for it (cancelled
     * meanwhile: its release is already queued behind the reservation).
     */
    @Transactional
    public void seatReserved(UUID registrationId) {
        repository.findById(registrationId).ifPresentOrElse(registration -> {
            if (registration.confirmSeat()) {
                metrics.confirmed();
                log.info("Registration {} confirmed on event {}", registrationId, registration.getEventId());
            } else {
                log.info("Seat reserved for registration {} ignored: it is {}", registrationId,
                        registration.getStatus());
            }
        }, () -> log.warn("Seat reserved for unknown registration {}", registrationId));
    }

    /**
     * Answer of event-service: the seat is refused (event full, closed or unknown).
     *
     * @param reason the code of the refusal, counted as the rejection reason
     */
    @Transactional
    public void seatRejected(UUID registrationId, String reason) {
        repository.findById(registrationId).ifPresentOrElse(registration -> {
            if (registration.rejectSeat(reason)) {
                metrics.rejected(reason);
                log.info("Registration {} rejected on event {}: {}", registrationId, registration.getEventId(), reason);
            } else {
                log.info("Seat refusal for registration {} ignored: it is {}", registrationId,
                        registration.getStatus());
            }
        }, () -> log.warn("Seat refusal for unknown registration {}", registrationId));
    }

    private DuplicateRegistrationException duplicate(UUID eventId, UUID userId) {
        metrics.rejected(ALREADY_REGISTERED);
        return new DuplicateRegistrationException(eventId, userId);
    }

    /**
     * The registrations of other users do not exist for the caller: 404, like an unknown id, so that
     * registration ids cannot be probed.
     */
    public Registration get(UUID registrationId, UUID userId) {
        return repository.findById(registrationId)
                .filter(registration -> registration.belongsTo(userId))
                .orElseThrow(() -> new RegistrationNotFoundException(registrationId));
    }

    /**
     * Who registered to an event, with their display names: its organizer only (the event itself is public,
     * hence 403 rather than 404). One call to user-service per page; without it, the list is still served
     * with unknown names.
     *
     * @param status optional filter, {@code null} for every registration
     */
    public Page<EventRegistration> findByEvent(UUID eventId, RegistrationStatus status, UUID organizerId,
                                               Pageable pageable) {
        boolean organizer = events.organizerOf(eventId).map(organizerId::equals).orElse(false);
        if (!organizer) {
            throw new NotEventOrganizerException(eventId);
        }
        return registrationsOf(eventId, status, pageable);
    }

    /**
     * Moderation: an administrator reads who registered to any event. Audited, since it discloses the names of
     * attendees to someone other than the organizer.
     */
    public Page<EventRegistration> findByEventAsAdministrator(UUID eventId, RegistrationStatus status,
                                                             UUID administratorId, Pageable pageable) {
        log.info("Audit: administrator {} read the registrations of event {}", administratorId, eventId);
        return registrationsOf(eventId, status, pageable);
    }

    private Page<EventRegistration> registrationsOf(UUID eventId, RegistrationStatus status, Pageable pageable) {
        Page<Registration> page = status == null
                ? repository.findByEventId(eventId, pageable)
                : repository.findByEventIdAndStatus(eventId, status, pageable);
        Map<UUID, String> names = attendeeNames(page);
        return page.map(registration -> new EventRegistration(registration, names.get(registration.getUserId())));
    }

    /**
     * The caller's registrations, optionally for one event and in one status: "am I registered to this event?" is
     * {@code eventId}, newest first, the first one being PENDING or CONFIRMED.
     */
    public Page<Registration> findByUser(UUID userId, UUID eventId, RegistrationStatus status, Pageable pageable) {
        List<Specification<Registration>> criteria = new ArrayList<>();
        criteria.add(ofUser(userId));
        if (eventId != null) {
            criteria.add(forEvent(eventId));
        }
        if (status != null) {
            criteria.add(hasStatus(status));
        }
        return repository.findAll(Specification.allOf(criteria), pageable);
    }

    private Map<UUID, String> attendeeNames(Page<Registration> page) {
        Set<UUID> attendees = page.stream().map(Registration::getUserId).collect(Collectors.toSet());
        if (attendees.isEmpty()) {
            return Map.of();
        }
        try {
            return attendeeDirectory.namesOf(attendees);
        } catch (RuntimeException unavailable) {
            // Names only enrich the list: an outage (or a refused call, a missing role for instance) never fails it.
            log.warn("Attendee names unavailable, listing without them: {}", unavailable.toString());
            return Map.of();
        }
    }

    private static boolean isActiveRegistrationConflict(DataIntegrityViolationException failure) {
        String message = NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
        return message != null && message.contains(ACTIVE_REGISTRATION_INDEX);
    }
}
