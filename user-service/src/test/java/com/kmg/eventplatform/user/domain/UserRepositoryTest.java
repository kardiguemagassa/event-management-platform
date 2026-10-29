package com.kmg.eventplatform.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kmg.eventplatform.user.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(showSql = false)
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

    // PostgreSQL stores microseconds: truncate to compare round-tripped values
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    private UserRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAndReloadsAllFields() {
        User saved = repository.saveAndFlush(User.register(UUID.randomUUID(), "Ada Lovelace", "ada@example.com", NOW));
        entityManager.clear();

        User reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(reloaded.getEmail()).isEqualTo("ada@example.com");
        assertThat(reloaded.getCreatedAt()).isEqualTo(NOW);
        assertThat(reloaded.getVersion()).isZero();
    }

    @Test
    void existsByEmailAndIdNotIgnoresTheUserItself() {
        User ada = repository.saveAndFlush(User.register(UUID.randomUUID(), "Ada Lovelace", "ada@example.com", NOW));
        User grace = repository.saveAndFlush(User.register(UUID.randomUUID(), "Grace Hopper", "grace@example.com", NOW));

        assertThat(repository.existsByEmailAndIdNot("ada@example.com", ada.getId())).isFalse();
        assertThat(repository.existsByEmailAndIdNot("ada@example.com", grace.getId())).isTrue();
    }

    @Test
    void databaseRejectsDuplicateEmail() {
        repository.saveAndFlush(User.register(UUID.randomUUID(), "Ada Lovelace", "ada@example.com", NOW));

        assertThatThrownBy(() -> repository.saveAndFlush(User.register(UUID.randomUUID(), "Ada Bis", "ada@example.com", NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNonLowercaseEmailEvenIfApplicationIsBypassed() {
        assertThatThrownBy(() -> entityManager.createNativeQuery("""
                        INSERT INTO users (id, full_name, email, created_at, version)
                        VALUES (?, 'Bypass', 'Bypass@Example.com', now(), 0)
                        """)
                .setParameter(1, UUID.randomUUID())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("ck_users_email_lowercase");
    }

    @Test
    void versionIncrementsOnUpdate() {
        User user = repository.saveAndFlush(User.register(UUID.randomUUID(), "Ada Lovelace", "ada@example.com", NOW));
        assertThat(user.getVersion()).isZero();

        user.changeDetails("Ada King", "ada@example.com");
        repository.saveAndFlush(user);

        assertThat(user.getVersion()).isEqualTo(1L);
    }
}
