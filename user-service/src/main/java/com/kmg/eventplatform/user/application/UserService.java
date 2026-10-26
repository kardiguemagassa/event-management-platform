package com.kmg.eventplatform.user.application;

import com.kmg.eventplatform.user.domain.User;
import com.kmg.eventplatform.user.domain.UserRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User use cases. Knows nothing about HTTP: the API layer maps requests to commands
 * and exceptions to status codes.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository repository;
    private final Clock clock;

    public UserService(UserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public User register(RegisterUserCommand command) {
        String email = User.normalizeEmail(command.email());
        if (repository.existsByEmail(email)) {
            throw new EmailAlreadyUsedException(email);
        }
        User user = User.register(command.fullName(), email, clock.instant());
        try {
            // Flush now so a concurrent duplicate (check-then-act race) surfaces here, not at commit
            return repository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyUsedException(email);
        }
    }

    public User getById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }

    public Page<User> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional
    public User update(UUID id, UpdateUserCommand command) {
        User user = getById(id);
        String email = User.normalizeEmail(command.email());
        if (repository.existsByEmailAndIdNot(email, id)) {
            throw new EmailAlreadyUsedException(email);
        }
        user.changeDetails(command.fullName(), email);
        try {
            repository.flush();
            return user;
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyUsedException(email);
        }
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(getById(id));
    }
}
