package com.kmg.eventplatform.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UserTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Test
    void registerNormalizesInputAndAssignsIdentity() {
        User user = User.register("  Ada Lovelace  ", "  Ada.Lovelace@Example.COM ", NOW);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(user.getEmail()).isEqualTo("ada.lovelace@example.com");
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
        assertThat(user.getVersion()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void registerRejectsBlankFullName(String fullName) {
        assertThatThrownBy(() -> User.register(fullName, "ada@example.com", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fullName");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void registerRejectsBlankEmail(String email) {
        assertThatThrownBy(() -> User.register("Ada Lovelace", email, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    void emailNormalizationIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(User.normalizeEmail("ALICE@EXAMPLE.COM")).isEqualTo("alice@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void changeDetailsAppliesTheSameRules() {
        User user = User.register("Ada Lovelace", "ada@example.com", NOW);

        user.changeDetails(" Ada King ", "ADA.KING@example.com");

        assertThat(user.getFullName()).isEqualTo("Ada King");
        assertThat(user.getEmail()).isEqualTo("ada.king@example.com");
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void equalityIsBasedOnIdentity() {
        User ada = User.register("Ada Lovelace", "ada@example.com", NOW);
        User sameEmail = User.register("Ada Lovelace", "ada@example.com", NOW);

        assertThat(ada).isEqualTo(ada).isNotEqualTo(sameEmail);
    }
}
