package com.kmg.eventplatform.registration.application;

import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
import com.kmg.eventplatform.registration.domain.RegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Orchestrates user-service and event-service around the local registration.
 * <p>
 * Deliberately not {@code @Transactional}: no database connection is held during remote calls.
 * Each repository write runs in its own short transaction, and remote side effects are undone by compensation.
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final RegistrationRepository repository;
    private final UserDirectory users;
    private final EventCatalog events;
    private final Clock clock;

    public RegistrationService(RegistrationRepository repository, UserDirectory users, EventCatalog events,
                               Clock clock) {
        this.repository = repository;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    public Registration register(UUID eventId, UUID userId) {
        if (!users.exists(userId)) {
            throw new UserNotFoundException(userId);
        }
        if (!events.exists(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        // Generated before any side effect: it is the idempotency key of the seat reservation.
        UUID registrationId = UUID.randomUUID();
        reserveSeat(eventId, registrationId);
        try {
            return repository.saveAndFlush(Registration.confirm(registrationId, eventId, userId, clock.instant()));
        } catch (RuntimeException saveFailure) {
            releaseSeatQuietly(eventId, registrationId, saveFailure);
            throw saveFailure;
        }
    }

    /**
     * The seat is released first: if event-service is down, the registration stays CONFIRMED
     * and the client can retry safely, since releasing is idempotent.
     */
    public Registration cancel(UUID registrationId) {
        Registration registration = get(registrationId);
        if (registration.isCancelled()) {
            throw new RegistrationAlreadyCancelledException(registrationId);
        }
        events.releaseSeat(registration.getEventId(), registration.getId());
        registration.cancel(clock.instant());
        return repository.saveAndFlush(registration);
    }

    public Registration get(UUID registrationId) {
        return repository.findById(registrationId)
                .orElseThrow(() -> new RegistrationNotFoundException(registrationId));
    }

    public Page<Registration> findByEvent(UUID eventId, Pageable pageable) {
        return repository.findByEventId(eventId, pageable);
    }

    public Page<Registration> findByUser(UUID userId, Pageable pageable) {
        return repository.findByUserId(userId, pageable);
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
