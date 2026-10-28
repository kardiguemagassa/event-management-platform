package com.kmg.eventplatform.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventHasReservationsException;
import com.kmg.eventplatform.event.domain.EventRepository;
import com.kmg.eventplatform.event.domain.EventStatus;
import com.kmg.eventplatform.event.domain.InvalidEventStateException;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final EventDetails DETAILS =
            new EventDetails("Spring Meetup", "Talks", "Paris", NOW.plus(Duration.ofDays(30)), 50);

    @Mock
    private EventRepository repository;

    private EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Event existing() {
        Event event = Event.create(DETAILS, NOW);
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));
        return event;
    }

    @Test
    void createSavesDraftWithClockTime() {
        when(repository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Event event = service.create(DETAILS);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void getByIdThrowsWhenEventDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(EventNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    @Nested
    class ListEvents {

        private final Pageable pageable = PageRequest.of(0, 20);
        private final Page<Event> page = new PageImpl<>(List.of());

        @Test
        void withoutStatusReturnsAllEvents() {
            when(repository.findAll(pageable)).thenReturn(page);

            assertThat(service.list(null, pageable)).isSameAs(page);
        }

        @Test
        void withStatusFiltersByStatus() {
            when(repository.findByStatus(EventStatus.PUBLISHED, pageable)).thenReturn(page);

            assertThat(service.list(EventStatus.PUBLISHED, pageable)).isSameAs(page);
        }
    }

    @Test
    void updateDelegatesToTheEntityRules() {
        Event event = existing();
        EventDetails changed = new EventDetails("Java Day", null, "Lyon", NOW.plus(Duration.ofDays(40)), 80);

        Event updated = service.update(event.getId(), changed);

        assertThat(updated.getTitle()).isEqualTo("Java Day");
        assertThat(updated.getCapacity()).isEqualTo(80);
    }

    @Test
    void publishMovesDraftToPublished() {
        Event event = existing();

        assertThat(service.publish(event.getId()).getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publishOfCancelledEventIsRejected() {
        Event event = existing();
        service.cancel(event.getId());

        assertThatThrownBy(() -> service.publish(event.getId()))
                .isInstanceOf(InvalidEventStateException.class);
    }

    @Test
    void cancelMovesEventToCancelled() {
        Event event = existing();

        assertThat(service.cancel(event.getId()).getStatus()).isEqualTo(EventStatus.CANCELLED);
    }

    @Nested
    class Delete {

        @Test
        void deletesEventWithoutReservations() {
            Event event = existing();

            service.delete(event.getId());

            verify(repository).delete(event);
        }

        @Test
        void refusesToDeleteEventWithReservations() {
            Event event = existing();
            ReflectionTestUtils.setField(event, "reservedSeats", 3);

            assertThatThrownBy(() -> service.delete(event.getId()))
                    .isInstanceOf(EventHasReservationsException.class);
            verify(repository, never()).delete(any());
        }

        @Test
        void throwsWhenEventDoesNotExist() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(id)).isInstanceOf(EventNotFoundException.class);
            verify(repository, never()).delete(any());
        }
    }
}
