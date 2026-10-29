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
import java.sql.SQLException;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID ADA = UUID.randomUUID();

    @Mock
    private UserRepository repository;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private User existingAda() {
        User ada = User.register(ADA, "Ada Lovelace", "ada@example.com", NOW.minusSeconds(3600));
        when(repository.findById(ADA)).thenReturn(Optional.of(ada));
        return ada;
    }

    private static DataIntegrityViolationException violationOf(String constraint) {
        return new DataIntegrityViolationException("could not execute",
                new SQLException("duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    @Nested
    class SaveProfile {

        @Test
        void firstSaveCreatesTheProfileWithTheKeycloakIdAndNormalizedEmail() {
            when(repository.findById(ADA)).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

            SavedProfile saved = service.saveProfile(ADA, "Ada Lovelace", " ADA@Example.com ");

            assertThat(saved.created()).isTrue();
            assertThat(saved.user().getId()).isEqualTo(ADA);
            assertThat(saved.user().getEmail()).isEqualTo("ada@example.com");
            assertThat(saved.user().getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void laterSaveUpdatesTheProfileAndCopiesTheEmailFromTheToken() {
            User ada = existingAda();

            SavedProfile saved = service.saveProfile(ADA, "Ada King", "Ada.King@example.com");

            assertThat(saved.created()).isFalse();
            assertThat(saved.user()).isSameAs(ada);
            assertThat(ada.getFullName()).isEqualTo("Ada King");
            assertThat(ada.getEmail()).isEqualTo("ada.king@example.com");
            verify(repository).flush();
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        void emailOfAnotherProfileIsRejectedWithoutSaving() {
            when(repository.existsByEmailAndIdNot("grace@example.com", ADA)).thenReturn(true);

            assertThatThrownBy(() -> service.saveProfile(ADA, "Ada", "GRACE@example.com"))
                    .isInstanceOf(EmailAlreadyUsedException.class)
                    .hasMessageContaining("grace@example.com");
            verify(repository, never()).saveAndFlush(any());
        }

        @Test
        void concurrentSaveTakingTheEmailIsAnEmailConflict() {
            when(repository.findById(ADA)).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any(User.class))).thenThrow(violationOf(UserService.EMAIL_UNIQUE_CONSTRAINT));

            assertThatThrownBy(() -> service.saveProfile(ADA, "Ada", "ada@example.com"))
                    .isInstanceOf(EmailAlreadyUsedException.class);
        }

        @Test
        void concurrentFirstSavesOfTheSameProfileAreAConcurrentModification() {
            when(repository.findById(ADA)).thenReturn(Optional.empty());
            when(repository.saveAndFlush(any(User.class))).thenThrow(violationOf("users_pkey"));

            assertThatThrownBy(() -> service.saveProfile(ADA, "Ada", "ada@example.com"))
                    .isInstanceOf(OptimisticLockingFailureException.class)
                    .hasMessageContaining(ADA.toString());
        }

        @Test
        void violationWithoutDetailsIsAConcurrentModification() {
            existingAda();
            doThrow(new DataIntegrityViolationException(null))
                    .when(repository).flush();

            assertThatThrownBy(() -> service.saveProfile(ADA, "Ada", "ada@example.com"))
                    .isInstanceOf(OptimisticLockingFailureException.class);
        }
    }

    @Nested
    class GetById {

        @Test
        void returnsExistingProfile() {
            User ada = existingAda();

            assertThat(service.getById(ADA)).isSameAs(ada);
        }

        @Test
        void profileNotSavedYetIsNotFound() {
            when(repository.findById(ADA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getById(ADA))
                    .isInstanceOf(UserNotFoundException.class)
                    .hasMessageContaining(ADA.toString());
        }
    }

    @Test
    void listDelegatesPagination() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of());
        when(repository.findAll(pageable)).thenReturn(page);

        assertThat(service.list(pageable)).isSameAs(page);
    }

    @Nested
    class Delete {

        @Test
        void deletesExistingProfile() {
            User ada = existingAda();

            service.delete(ADA);

            verify(repository).delete(ada);
        }

        @Test
        void throwsWhenProfileDoesNotExistAndDeletesNothing() {
            when(repository.findById(ADA)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(ADA)).isInstanceOf(UserNotFoundException.class);
            verify(repository, never()).delete(any());
        }
    }
}
