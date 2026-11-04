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

    private Registration requested() {
        return Registration.request(id, eventId, userId, NOW);
    }

    @Test
    void aNewRegistrationWaitsForItsSeat() {
        Registration registration = requested();

        assertThat(registration.getId()).isEqualTo(id);
        assertThat(registration.getEventId()).isEqualTo(eventId);
        assertThat(registration.getUserId()).isEqualTo(userId);
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(registration.getStatus().isActive()).isTrue();
        assertThat(registration.getCreatedAt()).isEqualTo(NOW);
        assertThat(registration.getCancelledAt()).isNull();
        assertThat(registration.getRejectionReason()).isNull();
        assertThat(registration.getVersion()).isNull();
        assertThat(registration.isCancelled()).isFalse();
    }

    @Test
    void requestRejectsMissingArguments() {
        assertThatNullPointerException().isThrownBy(() -> Registration.request(null, eventId, userId, NOW));
        assertThatNullPointerException().isThrownBy(() -> Registration.request(id, null, userId, NOW));
        assertThatNullPointerException().isThrownBy(() -> Registration.request(id, eventId, null, NOW));
        assertThatNullPointerException().isThrownBy(() -> Registration.request(id, eventId, userId, null));
    }

    @Test
    void aReservedSeatConfirmsItOnce() {
        Registration registration = requested();

        assertThat(registration.confirmSeat()).isTrue();
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
        assertThat(registration.getStatus().isActive()).isTrue();
        assertThat(registration.confirmSeat()).as("reply delivered again").isFalse();
        assertThat(registration.rejectSeat("EVENT_FULL")).isFalse();
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CONFIRMED);
    }

    @Test
    void aRefusedSeatRejectsItWithItsReason() {
        Registration registration = requested();

        assertThat(registration.rejectSeat("EVENT_FULL")).isTrue();
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.REJECTED);
        assertThat(registration.getStatus().isActive()).isFalse();
        assertThat(registration.getRejectionReason()).isEqualTo("EVENT_FULL");
        assertThat(registration.confirmSeat()).isFalse();
        assertThatNullPointerException().isThrownBy(() -> requested().rejectSeat(null));
    }

    @Test
    void aReplyAfterCancellationChangesNothing() {
        Registration registration = requested();
        registration.cancel(LATER);

        assertThat(registration.confirmSeat()).isFalse();
        assertThat(registration.rejectSeat("EVENT_FULL")).isFalse();
        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(registration.getStatus().isActive()).isFalse();
    }

    @Test
    void registrationBelongsToItsAttendeeOnly() {
        Registration registration = requested();

        assertThat(registration.belongsTo(userId)).isTrue();
        assertThat(registration.belongsTo(UUID.randomUUID())).isFalse();
        assertThat(registration.belongsTo(null)).isFalse();
    }

    @Test
    void aConfirmedRegistrationIsCancelled() {
        Registration registration = Registrations.confirmed(id, eventId, userId, NOW);

        registration.cancel(LATER);

        assertThat(registration.getStatus()).isEqualTo(RegistrationStatus.CANCELLED);
        assertThat(registration.getCancelledAt()).isEqualTo(LATER);
        assertThat(registration.isCancelled()).isTrue();
    }

    @Test
    void cancelTwiceIsRejected() {
        Registration registration = requested();
        registration.cancel(LATER);

        assertThatThrownBy(() -> registration.cancel(LATER.plusSeconds(60)))
                .isInstanceOf(RegistrationAlreadyCancelledException.class)
                .extracting(e -> ((RegistrationRuleException) e).code())
                .isEqualTo("REGISTRATION_ALREADY_CANCELLED");
        assertThat(registration.getCancelledAt()).isEqualTo(LATER);
    }

    @Test
    void aRejectedRegistrationCannotBeCancelled() {
        Registration registration = requested();
        registration.rejectSeat("EVENT_FULL");

        assertThatThrownBy(() -> registration.cancel(LATER))
                .isInstanceOf(RegistrationRejectedException.class)
                .extracting(e -> ((RegistrationRuleException) e).code())
                .isEqualTo("REGISTRATION_REJECTED");
        assertThat(registration.getCancelledAt()).isNull();
    }
}
