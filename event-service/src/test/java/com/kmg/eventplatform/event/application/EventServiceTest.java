package com.kmg.eventplatform.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private static final UUID ORGANIZER = UUID.randomUUID();
    private static final UUID OTHER_ORGANIZER = UUID.randomUUID();

    @Mock
    private EventRepository repository;

    private EventService service;

    @BeforeEach
    void setUp() {
        service = new EventService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Event existing() {
        Event event = Event.create(DETAILS, ORGANIZER, NOW);
        when(repository.findById(event.getId())).thenReturn(Optional.of(event));
        return event;
    }

    private Event existingPublished() {
        Event event = existing();
        event.publish(NOW);
        return event;
    }

    @Test
    void createSavesDraftOwnedByTheCallerWithClockTime() {
        when(repository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Event event = service.create(DETAILS, ORGANIZER);

        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(event.getCreatedAt()).isEqualTo(NOW);
        assertThat(event.getOrganizerId()).isEqualTo(ORGANIZER);
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
    class Visibility {

        @Test
        void draftIsVisibleToItsOrganizer() {
            Event draft = existing();

            assertThat(service.getVisible(draft.getId(), ORGANIZER)).isSameAs(draft);
        }

        @Test
        void draftOfAnotherOrganizerDoesNotExist() {
            Event draft = existing();

            assertThatThrownBy(() -> service.getVisible(draft.getId(), OTHER_ORGANIZER))
                    .isInstanceOf(EventNotFoundException.class);
            assertThatThrownBy(() -> service.getVisible(draft.getId(), null))
                    .isInstanceOf(EventNotFoundException.class);
        }

        @Test
        void publishedEventIsVisibleToAnonymousCallers() {
            Event published = existingPublished();

            assertThat(service.getVisible(published.getId(), null)).isSameAs(published);
        }
    }

    @Nested
    class ListEvents {

        private final Pageable pageable = PageRequest.of(0, 20);
        private final Page<Event> page = new PageImpl<>(List.of());

        @Test
        void anonymousCallerSeesNoDraft() {
            when(repository.findByStatusNot(EventStatus.DRAFT, pageable)).thenReturn(page);

            assertThat(service.list(null, null, pageable)).isSameAs(page);
        }

        @Test
        void authenticatedCallerAlsoSeesOwnDrafts() {
            when(repository.findVisibleTo(ORGANIZER, EventStatus.DRAFT, pageable)).thenReturn(page);

            assertThat(service.list(null, ORGANIZER, pageable)).isSameAs(page);
        }

        @Test
        void draftFilterReturnsOwnDraftsOnly() {
            when(repository.findByStatusAndOrganizerId(EventStatus.DRAFT, ORGANIZER, pageable)).thenReturn(page);

            assertThat(service.list(EventStatus.DRAFT, ORGANIZER, pageable)).isSameAs(page);
        }

        @Test
        void draftFilterIsEmptyForAnonymousCallers() {
            assertThat(service.list(EventStatus.DRAFT, null, pageable)).isEmpty();
            verifyNoInteractions(repository);
        }

        @Test
        void publicStatusFilterIsTheSameForEveryone() {
            when(repository.findByStatus(EventStatus.PUBLISHED, pageable)).thenReturn(page);

            assertThat(service.list(EventStatus.PUBLISHED, null, pageable)).isSameAs(page);
        }
    }

    @Nested
    class Ownership {

        @Test
        void anotherOrganizerCannotChangeAPublishedEvent() {
            Event event = existingPublished();

            assertThatThrownBy(() -> service.cancel(event.getId(), OTHER_ORGANIZER))
                    .isInstanceOf(NotEventOrganizerException.class)
                    .hasMessageContaining(event.getId().toString());
            assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
        }

        @Test
        void anotherOrganizerDoesNotEvenSeeADraft() {
            Event draft = existing();

            assertThatThrownBy(() -> service.publish(draft.getId(), OTHER_ORGANIZER))
                    .isInstanceOf(EventNotFoundException.class);
            assertThat(draft.getStatus()).isEqualTo(EventStatus.DRAFT);
        }

        @Test
        void eventCreatedBeforeOwnershipCannotBeChanged() {
            Event legacy = existingPublished();
            ReflectionTestUtils.setField(legacy, "organizerId", null);

            assertThatThrownBy(() -> service.update(legacy.getId(), DETAILS, ORGANIZER))
                    .isInstanceOf(NotEventOrganizerException.class);
        }
    }

    @Test
    void updateDelegatesToTheEntityRules() {
        Event event = existing();
        EventDetails changed = new EventDetails("Java Day", null, "Lyon", NOW.plus(Duration.ofDays(40)), 80);

        Event updated = service.update(event.getId(), changed, ORGANIZER);

        assertThat(updated.getTitle()).isEqualTo("Java Day");
        assertThat(updated.getCapacity()).isEqualTo(80);
    }

    @Test
    void publishMovesDraftToPublished() {
        Event event = existing();

        assertThat(service.publish(event.getId(), ORGANIZER).getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publishOfCancelledEventIsRejected() {
        Event event = existing();
        service.cancel(event.getId(), ORGANIZER);

        assertThatThrownBy(() -> service.publish(event.getId(), ORGANIZER))
                .isInstanceOf(InvalidEventStateException.class);
    }

    @Test
    void cancelMovesEventToCancelled() {
        Event event = existing();

        assertThat(service.cancel(event.getId(), ORGANIZER).getStatus()).isEqualTo(EventStatus.CANCELLED);
    }

    @Nested
    class Delete {

        @Test
        void deletesEventWithoutReservations() {
            Event event = existing();

            service.delete(event.getId(), ORGANIZER);

            verify(repository).delete(event);
        }

        @Test
        void refusesToDeleteEventWithReservations() {
            Event event = existing();
            ReflectionTestUtils.setField(event, "reservedSeats", 3);

            assertThatThrownBy(() -> service.delete(event.getId(), ORGANIZER))
                    .isInstanceOf(EventHasReservationsException.class);
            verify(repository, never()).delete(any());
        }

        @Test
        void refusesToDeleteTheEventOfAnotherOrganizer() {
            Event event = existingPublished();

            assertThatThrownBy(() -> service.delete(event.getId(), OTHER_ORGANIZER))
                    .isInstanceOf(NotEventOrganizerException.class);
            verify(repository, never()).delete(any());
        }

        @Test
        void throwsWhenEventDoesNotExist() {
            UUID id = UUID.randomUUID();
            when(repository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(id, ORGANIZER)).isInstanceOf(EventNotFoundException.class);
            verify(repository, never()).delete(any());
        }
    }
}
