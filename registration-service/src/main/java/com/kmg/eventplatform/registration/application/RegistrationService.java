package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
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
 * Deliberately not {@code @Transactional}: no database connection is held during remote calls.
 * Each repository write runs in its own short transaction, and remote side effects are undone by compensation.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /** Partial unique index of migration V2. */
    static final String ACTIVE_REGISTRATION_INDEX = "uq_registrations_active_event_user";

    private final RegistrationRepository repository;
    private final EventCatalog events;
    private final Clock clock;
    private final RegistrationMetrics metrics;
    private final AttendeeDirectory attendeeDirectory;

    public RegistrationService(RegistrationRepository repository, EventCatalog events, Clock clock,
                               RegistrationMetrics metrics, AttendeeDirectory attendeeDirectory) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
        this.attendeeDirectory = attendeeDirectory;
    }

    /**
     * Counted: confirmed, or rejected with the code of the failure (event full, already registered, downstream
     * unavailable...).
     *
     * @param userId the authenticated attendee, from the access token
     */
    public Registration register(UUID eventId, UUID userId) {
        try {
            Registration confirmed = confirm(eventId, userId);
            metrics.confirmed();
            return confirmed;
        } catch (RegistrationFailureException rejected) {
            metrics.rejected(rejected.code());
            throw rejected;
        }
    }

    private Registration confirm(UUID eventId, UUID userId) {
        if (!events.exists(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        // Fast path with a clear error and no remote side effect. Not a guarantee under concurrency:
        // the unique index is, and is translated below.
        if (repository.existsByEventIdAndUserIdAndStatus(eventId, userId, RegistrationStatus.CONFIRMED)) {
            throw new DuplicateRegistrationException(eventId, userId);
        }

        // Generated before any side effect: it is the idempotency key of the seat reservation.
        UUID registrationId = UUID.randomUUID();
        reserveSeat(eventId, registrationId);
        try {
            Registration confirmed =
                    repository.saveAndFlush(Registration.confirm(registrationId, eventId, userId, clock.instant()));
            // Ids only, no personal data: the audit trail of the saga, correlated with its trace in Grafana.
            log.info("Registration {} confirmed on event {}", registrationId, eventId);
            return confirmed;
        } catch (RuntimeException saveFailure) {
            releaseSeatQuietly(eventId, registrationId, saveFailure);
            if (isActiveRegistrationConflict(saveFailure)) {
                throw new DuplicateRegistrationException(eventId, userId);
            }
            throw saveFailure;
        }
    }

    /**
     * The seat is released first: if event-service is down, the registration stays CONFIRMED
     * and the client can retry safely, since releasing is idempotent.
     */
    public Registration cancel(UUID registrationId, UUID userId) {
        Registration registration = get(registrationId, userId);
        if (registration.isCancelled()) {
            throw new RegistrationAlreadyCancelledException(registrationId);
        }
        events.releaseSeat(registration.getEventId(), registration.getId());
        registration.cancel(clock.instant());
        Registration cancelled = repository.saveAndFlush(registration);
        metrics.cancelled();
        log.info("Registration {} cancelled on event {}", registrationId, registration.getEventId());
        return cancelled;
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
        Page<Registration> page = status == null
                ? repository.findByEventId(eventId, pageable)
                : repository.findByEventIdAndStatus(eventId, status, pageable);
        Map<UUID, String> names = attendeeNames(page);
        return page.map(registration -> new EventRegistration(registration, names.get(registration.getUserId())));
    }

    /**
     * The caller's registrations, optionally for one event and in one status: "am I registered to this event?" is
     * {@code eventId} with {@code CONFIRMED}.
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

    /**
     * A timeout does not tell whether event-service committed the reservation: release it to be sure.
     */
    private void reserveSeat(UUID eventId, UUID registrationId) {
        try {
            events.reserveSeat(eventId, registrationId);
        } catch (DownstreamUnavailableException unknownOutcome) {
            releaseSeatQuietly(eventId, registrationId, unknownOutcome);
            throw unknownOutcome;
        }
    }

    private static boolean isActiveRegistrationConflict(RuntimeException failure) {
        if (!(failure instanceof DataIntegrityViolationException)) {
            return false;
        }
        String message = NestedExceptionUtils.getMostSpecificCause(failure).getMessage();
        return message != null && message.contains(ACTIVE_REGISTRATION_INDEX);
    }

    /**
     * Best-effort compensation: never hides the original failure.
     */
    private void releaseSeatQuietly(UUID eventId, UUID registrationId, RuntimeException originalFailure) {
        try {
            events.releaseSeat(eventId, registrationId);
        } catch (RuntimeException releaseFailure) {
            originalFailure.addSuppressed(releaseFailure);
            log.error("Compensation failed: seat of reservation {} on event {} may still be held",
                    registrationId, eventId, releaseFailure);
        }
    }
}
