package com.kmg.eventplatform.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class EventTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final Instant NEXT_MONTH = NOW.plus(Duration.ofDays(30));

    private static EventDetails details(int capacity) {
        return new EventDetails("Spring Meetup", "Talks about Spring Boot 4", "Paris", NEXT_MONTH, capacity);
    }

    private static Event draft() {
        return Event.create(details(50), NOW);
    }

    private static void reserveSeats(Event event, int seats) {
        ReflectionTestUtils.setField(event, "reservedSeats", seats);
    }

    @Nested
    class Details {

        @Test
        void normalizesTextAndBlankDescription() {
            EventDetails details = new EventDetails("  Meetup ", "   ", " Paris ", NEXT_MONTH, 10);

            assertThat(details.title()).isEqualTo("Meetup");
            assertThat(details.location()).isEqualTo("Paris");
            assertThat(details.description()).isNull();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "   ")
        void rejectsBlankTitle(String title) {
            assertThatThrownBy(() -> new EventDetails(title, null, "Paris", NEXT_MONTH, 10))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        void rejectsNonPositiveCapacity(int capacity) {
            assertThatThrownBy(() -> new EventDetails("Meetup", null, "Paris", NEXT_MONTH, capacity))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }
    }

    @Nested
    class Create {

        @Test
        void startsAsDraftWithNoReservation() {
            Event event = draft();

            assertThat(event.getId()).isNotNull();
            assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
            assertThat(event.getReservedSeats()).isZero();
            assertThat(event.availableSeats()).isEqualTo(50);
            assertThat(event.getCreatedAt()).isEqualTo(NOW);
        }

        @Test
        void rejectsStartInThePast() {
            EventDetails past = new EventDetails("Meetup", null, "Paris", NOW.minusSeconds(1), 10);

            assertThatThrownBy(() -> Event.create(past, NOW))
                    .isInstanceOf(EventStartNotInFutureException.class)
                    .extracting(e -> ((EventRuleException) e).code())
                    .isEqualTo("EVENT_START_NOT_IN_FUTURE");
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void draftCanBePublished() {
            Event event = draft();

            event.publish(NOW);

            assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        }

        @Test
        void publishIsIdempotent() {
            Event event = draft();
            event.publish(NOW);

            event.publish(NOW);

            assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        }

        @Test
        void cancelledEventCannotBePublished() {
            Event event = draft();
            event.cancel();

            assertThatThrownBy(() -> event.publish(NOW))
                    .isInstanceOf(InvalidEventStateException.class)
                    .hasMessageContaining("CANCELLED");
        }

        @Test
        void eventThatAlreadyStartedCannotBePublished() {
            Event event = draft();

            assertThatThrownBy(() -> event.publish(NEXT_MONTH.plusSeconds(1)))
                    .isInstanceOf(EventStartNotInFutureException.class);
        }

        @Test
        void draftAndPublishedCanBeCancelledAndCancelIsIdempotent() {
            Event draft = draft();
            Event published = draft();
            published.publish(NOW);

            draft.cancel();
            published.cancel();
            published.cancel();

            assertThat(draft.getStatus()).isEqualTo(EventStatus.CANCELLED);
            assertThat(published.getStatus()).isEqualTo(EventStatus.CANCELLED);
        }
    }

    @Nested
    class Update {

        @Test
        void replacesDetails() {
            Event event = draft();
            EventDetails changed = new EventDetails("Java Day", null, "Lyon", NEXT_MONTH.plusSeconds(60), 80);

            event.update(changed, NOW);

            assertThat(event.getTitle()).isEqualTo("Java Day");
            assertThat(event.getLocation()).isEqualTo("Lyon");
            assertThat(event.getCapacity()).isEqualTo(80);
        }

        @Test
        void cancelledEventCannotBeUpdated() {
            Event event = draft();
            event.cancel();

            assertThatThrownBy(() -> event.update(details(50), NOW))
                    .isInstanceOf(InvalidEventStateException.class);
        }

        @Test
        void capacityCannotGoBelowReservedSeats() {
            Event event = draft();
            reserveSeats(event, 10);

            assertThatThrownBy(() -> event.update(details(9), NOW))
                    .isInstanceOf(CapacityBelowReservedException.class)
                    .extracting(e -> ((EventRuleException) e).code())
                    .isEqualTo("CAPACITY_BELOW_RESERVED");
        }

        @Test
        void capacityCanEqualReservedSeats() {
            Event event = draft();
            reserveSeats(event, 10);

            event.update(details(10), NOW);

            assertThat(event.availableSeats()).isZero();
        }
    }

    @Nested
    class RegistrationRules {

        @Test
        void onlyPublishedFutureEventsAreRegistrable() {
            Event event = draft();
            assertThat(event.isRegistrable(NOW)).isFalse();

            event.publish(NOW);
            assertThat(event.isRegistrable(NOW)).isTrue();
            assertThat(event.isRegistrable(NEXT_MONTH)).isFalse();

            event.cancel();
            assertThat(event.isRegistrable(NOW)).isFalse();
        }

        @Test
        void eventWithReservationsCannotBeDeleted() {
            Event event = draft();
            event.ensureDeletable();

            reserveSeats(event, 1);

            assertThatThrownBy(event::ensureDeletable)
                    .isInstanceOf(EventHasReservationsException.class);
        }
    }
}
