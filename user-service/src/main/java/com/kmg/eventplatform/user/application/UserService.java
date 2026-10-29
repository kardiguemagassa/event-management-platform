package com.kmg.eventplatform.user.application;

import com.kmg.eventplatform.user.domain.User;
import com.kmg.eventplatform.user.domain.UserRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile use cases. Knows nothing about HTTP: the API layer maps requests to calls and exceptions to status
 * codes. The caller's id and e-mail come from the access token, never from the request body.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

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

    public Page<User> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(getById(id));
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
