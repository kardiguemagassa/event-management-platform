package com.kmg.eventplatform.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kmg.eventplatform.event.application.EventNotFoundException;
import com.kmg.eventplatform.event.application.EventSearch;
import com.kmg.eventplatform.event.application.EventService;
import com.kmg.eventplatform.event.application.NotEventOrganizerException;
import com.kmg.eventplatform.event.domain.CapacityBelowReservedException;
import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventDetails;
import com.kmg.eventplatform.event.domain.EventHasReservationsException;
import com.kmg.eventplatform.event.domain.EventStartNotInFutureException;
import com.kmg.eventplatform.event.domain.EventStatus;
import com.kmg.eventplatform.event.domain.InvalidEventStateException;
import com.kmg.eventplatform.event.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(EventController.class)
@Import(SecurityConfig.class)
class EventControllerTest {

    private static final UUID ORGANIZER = UUID.randomUUID();

    private static final Instant START = Instant.parse("2099-06-01T18:00:00Z");
    private static final EventDetails DETAILS = new EventDetails("Spring Meetup", "Talks", "Paris", START, 50);
    private static final String VALID_JSON = """
            {"title": "Spring Meetup", "description": "Talks", "location": "Paris",
             "startAt": "2099-06-01T18:00:00Z", "capacity": 50}
            """;

    @Autowired
    private WebApplicationContext context;

    /** With the security filter chain and no credentials. */
    @Autowired
    private MockMvc anonymous;

    /** Every request is made by ORGANIZER, with the ORGANIZER role: these tests are about the API contract. */
    private MockMvc mockMvc;

    @MockitoBean
    private EventService service;

    /** Tokens are built by jwt() below: no decoding, no Keycloak. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void authenticateEveryRequestAsTheOrganizer() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(caller(ORGANIZER, "ORGANIZER")))
                .build();
    }

    private static JwtRequestPostProcessor caller(UUID userId, String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return jwt().jwt(token -> token.subject(userId.toString())).authorities(authorities);
    }

    private static Event event() {
        return Event.create(DETAILS, ORGANIZER, Instant.now());
    }

    @Test
    void anonymousCallerCanReadAPublicEvent() throws Exception {
        Event event = event();
        event.publish(Instant.now());
        when(service.getVisible(event.getId(), null)).thenReturn(event);

        anonymous.perform(get("/api/events/{id}", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizerId").value(ORGANIZER.toString()));
    }

    @Test
    void anonymousCallerCannotCreateAnEvent() throws Exception {
        anonymous.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    @Test
    void attendeeCannotCreateAnEvent() throws Exception {
        anonymous.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID_JSON)
                        .with(caller(UUID.randomUUID(), "ATTENDEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void anotherOrganizerCannotChangeTheEvent() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.cancel(id, ORGANIZER)).thenThrow(new NotEventOrganizerException(id));

        mockMvc.perform(post("/api/events/{id}/cancel", id))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_EVENT_ORGANIZER"))
                .andExpect(jsonPath("$.eventId").value(id.toString()));
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        Event event = event();
        when(service.create(DETAILS, ORGANIZER)).thenReturn(event);

        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/events/" + event.getId())))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.capacity").value(50))
                .andExpect(jsonPath("$.reservedSeats").value(0))
                .andExpect(jsonPath("$.availableSeats").value(50))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void createRejectsInvalidPayloadWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": " ", "location": "", "startAt": "2000-01-01T00:00:00Z", "capacity": 0}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.location").exists())
                .andExpect(jsonPath("$.errors.startAt").exists())
                .andExpect(jsonPath("$.errors.capacity").exists());

        verify(service, never()).create(any(), any());
    }

    @Test
    void createRejectsMissingCapacity() throws Exception {
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title": "Meetup", "location": "Paris", "startAt": "2099-06-01T18:00:00Z"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.capacity").exists());
    }

    @Test
    void domainStartRuleIsReturnedAs400() throws Exception {
        when(service.create(any(), any())).thenThrow(new EventStartNotInFutureException(null, START));

        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_START_NOT_IN_FUTURE"))
                .andExpect(jsonPath("$.eventId").doesNotExist());
    }

    @Test
    void getReturnsEvent() throws Exception {
        Event event = event();
        when(service.getVisible(event.getId(), ORGANIZER)).thenReturn(event);

        mockMvc.perform(get("/api/events/{id}", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Spring Meetup"))
                .andExpect(jsonPath("$.startAt").value("2099-06-01T18:00:00Z"));
    }

    @Test
    void getReturns404WhenEventDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getVisible(id, ORGANIZER)).thenThrow(new EventNotFoundException(id));

        mockMvc.perform(get("/api/events/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"))
                .andExpect(jsonPath("$.eventId").value(id.toString()));
    }

    @Test
    void listDefaultsToAllEventsSortedByStartAtAscending() throws Exception {
        when(service.list(eq(EventSearch.all()), eq(ORGANIZER), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(event()), invocation.getArgument(2), 1));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.size").value(20));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(eq(EventSearch.all()), eq(ORGANIZER), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("startAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listPassesEveryFilterToTheService() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.list(any(), eq(ORGANIZER), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.getArgument(2), 0));

        mockMvc.perform(get("/api/events")
                        .param("status", "PUBLISHED")
                        .param("q", "  Paris ")
                        .param("from", "2026-12-01T00:00:00Z")
                        .param("organizer", "me")
                        .param("ids", first + "," + second + "," + first))
                .andExpect(status().isOk());

        ArgumentCaptor<EventSearch> search = ArgumentCaptor.forClass(EventSearch.class);
        verify(service).list(search.capture(), eq(ORGANIZER), any());
        assertThat(search.getValue()).isEqualTo(new EventSearch(EventStatus.PUBLISHED, "Paris",
                Instant.parse("2026-12-01T00:00:00Z"), EventSearch.Scope.MINE, Set.of(first, second)));
    }

    @Test
    void anonymousCallerBrowsesTheCatalogWithFilters() throws Exception {
        when(service.list(any(), isNull(), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.getArgument(2), 0));

        anonymous.perform(get("/api/events").param("q", "kafka"))
                .andExpect(status().isOk());
    }

    @Test
    void ownEventsRequireAToken() throws Exception {
        anonymous.perform(get("/api/events").param("organizer", "me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    @Test
    void ownEventsRequireTheOrganizerRole() throws Exception {
        anonymous.perform(get("/api/events").param("organizer", "me").with(caller(UUID.randomUUID(), "ATTENDEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void administratorListsEveryEvent() throws Exception {
        UUID admin = UUID.randomUUID();
        when(service.list(any(), eq(admin), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(event()), invocation.getArgument(2), 1));

        anonymous.perform(get("/api/events").param("scope", "all").param("status", "DRAFT")
                        .with(caller(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(service).list(eq(new EventSearch(EventStatus.DRAFT, null, null, EventSearch.Scope.ALL, null)),
                eq(admin), any());
    }

    @Test
    void everyEventRequiresTheAdminRole() throws Exception {
        mockMvc.perform(get("/api/events").param("scope", "all"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        anonymous.perform(get("/api/events").param("scope", "all"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void scopeRejectsOtherValuesAndTheOrganizerFilter() throws Exception {
        UUID admin = UUID.randomUUID();

        anonymous.perform(get("/api/events").param("scope", "everything").with(caller(admin, "ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        anonymous.perform(get("/api/events").param("scope", "all").param("organizer", "me")
                        .with(caller(admin, "ADMIN", "ORGANIZER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(service);
    }

    @Test
    void administratorReadsADraft() throws Exception {
        Event draft = event();
        when(service.getById(draft.getId())).thenReturn(draft);

        anonymous.perform(get("/api/events/{id}", draft.getId()).with(caller(UUID.randomUUID(), "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        verify(service, never()).getVisible(any(), any());
    }

    @Test
    void administratorCancelsAnyEvent() throws Exception {
        UUID admin = UUID.randomUUID();
        Event event = event();
        event.cancel();
        when(service.cancelAsAdministrator(event.getId(), admin)).thenReturn(event);

        anonymous.perform(post("/api/events/{id}/cancel", event.getId()).with(caller(admin, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(service, never()).cancel(any(), any());
    }

    @Test
    void attendeeCannotCancelAnEvent() throws Exception {
        anonymous.perform(post("/api/events/{id}/cancel", UUID.randomUUID())
                        .with(caller(UUID.randomUUID(), "ATTENDEE")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void listRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/events").param("status", "OPEN"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.status").exists());

        verifyNoInteractions(service);
    }

    @Test
    void listRejectsMalformedFromAndIds() throws Exception {
        mockMvc.perform(get("/api/events").param("from", "tomorrow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.from").exists());
        mockMvc.perform(get("/api/events").param("ids", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.ids").exists());

        verifyNoInteractions(service);
    }

    @Test
    void listRejectsInvalidFilters() throws Exception {
        String tooManyIds = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        mockMvc.perform(get("/api/events").param("organizer", "someone-else"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.organizer").exists());
        mockMvc.perform(get("/api/events").param("ids", tooManyIds))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").exists());
        mockMvc.perform(get("/api/events").param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.q").exists());

        verifyNoInteractions(service);
    }

    @Test
    void listRejectsNonWhitelistedSort() throws Exception {
        mockMvc.perform(get("/api/events").param("sort", "reservedSeats,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"));

        verifyNoInteractions(service);
    }

    @Test
    void updateReturns409WhenCapacityBelowReservedSeats() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any(), eq(ORGANIZER))).thenThrow(new CapacityBelowReservedException(id, 50, 60));

        mockMvc.perform(put("/api/events/{id}", id).contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPACITY_BELOW_RESERVED"))
                .andExpect(jsonPath("$.eventId").value(id.toString()));
    }

    @Test
    void updateReturns409OnConcurrentModification() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any(), eq(ORGANIZER))).thenThrow(new OptimisticLockingFailureException("stale"));

        mockMvc.perform(put("/api/events/{id}", id).contentType(MediaType.APPLICATION_JSON).content(VALID_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void publishReturnsPublishedEvent() throws Exception {
        Event event = event();
        event.publish(Instant.now());
        when(service.publish(event.getId(), ORGANIZER)).thenReturn(event);

        mockMvc.perform(post("/api/events/{id}/publish", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void publishReturns409WhenEventIsCancelled() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.publish(id, ORGANIZER)).thenThrow(new InvalidEventStateException(id, EventStatus.CANCELLED, "publish"));

        mockMvc.perform(post("/api/events/{id}/publish", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_STATE"))
                .andExpect(jsonPath("$.title").value("Invalid event state"));
    }

    @Test
    void cancelReturnsCancelledEvent() throws Exception {
        Event event = event();
        event.cancel();
        when(service.cancel(event.getId(), ORGANIZER)).thenReturn(event);

        mockMvc.perform(post("/api/events/{id}/cancel", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/events/{id}", id))
                .andExpect(status().isNoContent());

        verify(service).delete(id, ORGANIZER);
    }

    @Test
    void deleteReturns409WhenEventHasReservations() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new EventHasReservationsException(id, 3)).when(service).delete(id, ORGANIZER);

        mockMvc.perform(delete("/api/events/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_HAS_RESERVATIONS"));
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getVisible(id, ORGANIZER)).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(get("/api/events/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }
}
