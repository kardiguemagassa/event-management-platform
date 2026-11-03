package com.kmg.eventplatform.user.application;

import static com.kmg.eventplatform.user.domain.UserSpecifications.idIn;
import static com.kmg.eventplatform.user.domain.UserSpecifications.nameOrEmailContains;

import com.kmg.eventplatform.user.domain.User;
import com.kmg.eventplatform.user.domain.UserRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile use cases. Knows nothing about HTTP: the API layer maps requests to calls and exceptions to status
 * codes. The caller's id and e-mail come from the access token, never from the request body.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** Unique constraint of migration V1. */
    static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";

    private final UserRepository repository;
    private final Clock clock;

    public UserService(UserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Creates the profile of the user on first save, updates it afterwards. Idempotent: the e-mail is copied
     * from the token on every save, so a change made in Keycloak reaches the profile.
     */
    @Transactional
    public SavedProfile saveProfile(UUID id, String fullName, String email) {
        String normalizedEmail = User.normalizeEmail(email);
        if (repository.existsByEmailAndIdNot(normalizedEmail, id)) {
            throw new EmailAlreadyUsedException(normalizedEmail);
        }
        Optional<User> existing = repository.findById(id);
        try {
            if (existing.isPresent()) {
                User user = existing.get();
                user.changeDetails(fullName, normalizedEmail);
                repository.flush();
                return new SavedProfile(user, false);
            }
            // Flush now so a concurrent save (check-then-act race) surfaces here, not at commit.
            User created = repository.saveAndFlush(User.register(id, fullName, normalizedEmail, clock.instant()));
            return new SavedProfile(created, true);
        } catch (DataIntegrityViolationException e) {
            throw translate(e, id, normalizedEmail);
        }
    }

    public User getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    /** Profiles of these users; users without a profile are left out. */
    public List<User> findAllById(Collection<UUID> ids) {
        return repository.findAllById(ids);
    }

    /**
     * Profiles for the administration, optionally filtered.
     *
     * @param text name or e-mail containing this text (case-insensitive), {@code null} or blank for any
     * @param ids  only these users, {@code null} or empty for any
     */
    public Page<User> list(String text, Collection<UUID> ids, Pageable pageable) {
        List<Specification<User>> criteria = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            criteria.add(nameOrEmailContains(text.strip()));
        }
        if (ids != null && !ids.isEmpty()) {
            criteria.add(idIn(ids));
        }
        return repository.findAll(Specification.allOf(criteria), pageable);
    }

    /** Right to erasure: the caller deletes their own profile. */
    @Transactional
    public void delete(UUID id) {
        repository.delete(getById(id));
    }

    /** An administrator deletes the profile of another user. Audited: it erases someone else's personal data. */
    @Transactional
    public void deleteAsAdministrator(UUID id, UUID administratorId) {
        repository.delete(getById(id));
        log.info("Audit: administrator {} deleted the profile of user {}", administratorId, id);
    }

    /**
     * Two saves raced: on the e-mail (another user took it), or on the id (two first saves of the same profile,
     * a double click for instance), which is reported like any concurrent modification.
     */
    private static RuntimeException translate(DataIntegrityViolationException e, UUID id, String email) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        if (message != null && message.contains(EMAIL_UNIQUE_CONSTRAINT)) {
            return new EmailAlreadyUsedException(email);
        }
        return new OptimisticLockingFailureException("Profile " + id + " was saved concurrently", e);
    }
}
