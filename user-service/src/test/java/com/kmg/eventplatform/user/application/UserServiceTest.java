package com.kmg.eventplatform.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kmg.eventplatform.user.domain.User;
import com.kmg.eventplatform.user.domain.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Mock
    private UserRepository repository;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static User existingUser() {
        return User.register("Ada Lovelace", "ada@example.com", NOW);
    }

    @Nested
    class Register {

        @Test
        void savesNormalizedUserWithClockTime() {
            when(repository.existsByEmail("ada@example.com")).thenReturn(false);
            when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            User user = service.register(new RegisterUserCommand("Ada Lovelace", " ADA@Example.com "));

            assertThat(user.getEmail()).isEqualTo("ada@example.com");
            assertThat(user.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void rejectsAlreadyUsedEmailWithoutSaving() {
            when(repository.existsByEmail("ada@example.com")).thenReturn(true);

            assertThatThrownBy(() -> service.register(new RegisterUserCommand("Ada", "ADA@example.com")))
                    .isInstanceOf(EmailAlreadyUsedException.class)
                    .hasMessageContaining("ada@example.com");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        void translatesConcurrentDuplicateIntoEmailAlreadyUsed() {
            when(repository.existsByEmail("ada@example.com")).thenReturn(false);
            when(repository.saveAndFlush(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_users_email"));

            assertThatThrownBy(() -> service.register(new RegisterUserCommand("Ada", "ada@example.com")))
                    .isInstanceOf(EmailAlreadyUsedException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsExistingUser() {
            User ada = existingUser();
            when(repository.findById(ada.getId())).thenReturn(Optional.of(ada));

            assertThat(service.getById(ada.getId())).isSameAs(ada);
        }

        @Test
        void throwsWhenUserDoesNotExist() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(id))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining(id.toString());
        }
    }

    @Test
    void listDelegatesPagination() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of(existingUser()), pageable, 1);
        when(repository.findAll(pageable)).thenReturn(page);

        assertThat(service.list(pageable)).isSameAs(page);
    }

    @Nested
    class Update {

        @Test
        void changesDetailsAndFlushes() {
            User ada = existingUser();
            when(repository.findById(ada.getId())).thenReturn(Optional.of(ada));
            when(repository.existsByEmailAndIdNot("ada.king@example.com", ada.getId())).thenReturn(false);

            User updated = service.update(ada.getId(), new UpdateUserCommand("Ada King", "Ada.King@example.com"));

            assertThat(updated.getFullName()).isEqualTo("Ada King");
            assertThat(updated.getEmail()).isEqualTo("ada.king@example.com");
            verify(repository).flush();
        }

        @Test
        void rejectsEmailUsedByAnotherUser() {
            User ada = existingUser();
            when(repository.findById(ada.getId())).thenReturn(Optional.of(ada));
            when(repository.existsByEmailAndIdNot("grace@example.com", ada.getId())).thenReturn(true);

            assertThatThrownBy(() -> service.update(ada.getId(), new UpdateUserCommand("Ada", "grace@example.com")))
                    .isInstanceOf(EmailAlreadyUsedException.class);
            assertThat(ada.getEmail()).isEqualTo("ada@example.com");
        }

        @Test
        void translatesConcurrentDuplicateIntoEmailAlreadyUsed() {
            User ada = existingUser();
            when(repository.findById(ada.getId())).thenReturn(Optional.of(ada));
            when(repository.existsByEmailAndIdNot("grace@example.com", ada.getId())).thenReturn(false);
            doThrow(new DataIntegrityViolationException("uk_users_email")).when(repository).flush();

            assertThatThrownBy(() -> service.update(ada.getId(), new UpdateUserCommand("Ada", "grace@example.com")))
                    .isInstanceOf(EmailAlreadyUsedException.class);
        }

        @Test
        void throwsWhenUserDoesNotExist() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(id, new UpdateUserCommand("Ada", "ada@example.com")))
                    .isInstanceOf(UserNotFoundException.class);
        }
    }

    @Nested
    class Delete {

        @Test
        void deletesExistingUser() {
            User ada = existingUser();
            when(repository.findById(ada.getId())).thenReturn(Optional.of(ada));

            service.delete(ada.getId());

            verify(repository).delete(ada);
        }

        @Test
        void throwsWhenUserDoesNotExistAndDeletesNothing() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(id)).isInstanceOf(UserNotFoundException.class);
            verify(repository, never()).delete(any());
        }
    }
}
