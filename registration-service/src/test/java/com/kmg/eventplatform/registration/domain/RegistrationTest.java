package com.kmg.eventplatform.registration.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistrationTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    private final UUID id = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void confirmCreatesConfirmedRegistration() {
        Registration registration = Registration.confirm(id, eventId, userId, NOW);

        assertThat(registration.getId()).isEqualTo(id);
        assertThat(registration.getEventId()).isEqualTo(eventId);
        assertThat(registration.getUserId()).isEqualTo(userId);
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.getCreatedAt()).isEqualTo(NOW);
        assertThat(registration.getCancelledAt()).isNull();
        assertThat(registration.getVersion()).isNull();
        assertThat(registration.isCancelled()).isFalse();
    }

    @Test
    void confirmRejectsMissingArguments() {
        assertThatNullPointerException().isThrownBy(() -> Registration.confirm(null, eventId, userId, NOW));
        assertThatNullPointerException().isThrownBy(() -> Registration.confirm(id, null, userId, NOW));
        assertThatNullPointerException().isThrownBy(() -> Registration.confirm(id, eventId, null, NOW));
        assertThatNullPointerException().isThrownBy(() -> Registration.confirm(id, eventId, userId, null));
    }

    @Test
    void cancelMarksRegistrationCancelled() {
        Registration registration = Registration.confirm(id, eventId, userId, NOW);

        registration.cancel(LATER);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(registration.getCancelledAt()).isEqualTo(LATER);
        assertThat(registration.isCancelled()).isTrue();
    }

    @Test
    void cancelTwiceIsRejected() {
        Registration registration = Registration.confirm(id, eventId, userId, NOW);
        registration.cancel(LATER);

        assertThatThrownBy(() -> registration.cancel(LATER.plusSeconds(60)))
                .isInstanceOf(RegistrationAlreadyCancelledException.class)
                .extracting(e -> ((RegistrationRuleException) e).code())
                .isEqualTo("REGISTRATION_ALREADY_CANCELLED");
        assertThat(registration.getCancelledAt()).isEqualTo(LATER);
    }
}
