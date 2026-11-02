package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.registration.application.EventRegistration;
import com.kmg.eventplatform.registration.application.NotEventOrganizerException;
import com.kmg.eventplatform.registration.application.RegistrationNotFoundException;
import com.kmg.eventplatform.registration.application.RegistrationService;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
import com.kmg.eventplatform.registration.domain.RegistrationStatus;
import com.kmg.eventplatform.registration.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RegistrationController.class)
@Import(SecurityConfig.class)
class RegistrationControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    @Autowired
    private WebApplicationContext context;

    /** With the security filter chain and no credentials. */
    @Autowired
    private MockMvc anonymous;

    /** Every request is made by userId, attendee and organizer: these tests are about the API contract. */
    private MockMvc mockMvc;

    /** Tokens are built by jwt() below: no decoding, no Keycloak. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RegistrationService service;

    private final UUID eventId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Registration registration = Registration.confirm(UUID.randomUUID(), eventId, userId, NOW);

    @BeforeEach
    void authenticateEveryRequest() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultRequest(get("/").with(caller(userId, "ATTENDEE", "ORGANIZER")))
                .build();
    }

    private static JwtRequestPostProcessor caller(UUID id, String... roles) {
        SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
        }
        return jwt().jwt(token -> token.subject(id.toString())).authorities(authorities);
    }

    @Test
    void onlyAttendeesRegister() throws Exception {
        anonymous.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\"}")
                        .with(caller(userId, "ORGANIZER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void userIdSentInTheBodyIsIgnored() throws Exception {
        UUID someoneElse = UUID.randomUUID();
        when(service.register(eventId, userId)).thenReturn(registration);

        mockMvc.perform(post("/api/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":\"" + eventId + "\",\"userId\":\"" + someoneElse + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void onlyOrganizersListTheRegistrationsOfAnEvent() throws Exception {
        anonymous.perform(get("/api/registrations/events/{eventId}", eventId).with(caller(userId, "ATTENDEE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(service);
    }

    @Test
    void organizerOfAnotherEventIsForbidden() throws Exception {
        when(service.findByEvent(eq(eventId), isNull(), eq(userId), argThat(newestFirst())))
                .thenThrow(new NotEventOrganizerException(eventId));

        mockMvc.perform(get("/api/registrations/events/{eventId}", eventId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_EVENT_ORGANIZER"));
    }

    @Test
    void mineListsTheCallersRegistrations() throws Exception {
        when(service.findByUser(eq(userId), isNull(), isNull(), argThat(newestFirst())))
                .thenReturn(new PageImpl<>(List.of(registration), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/registrations/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(userId.toString()));
    }

    @Test
    void mineFiltersByEventAndStatus() throws Exception {
        when(service.findByUser(eq(userId), eq(eventId), eq(RegistrationStatus.CONFIRMED), argThat(newestFirst())))
                .thenReturn(new PageImpl<>(List.of(registration), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/registrations/me").param("eventId", eventId.toString()).param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(registration.getId().toString()));
    }

    @Test
    void mineRejectsAMalformedEventId() throws Exception {
        mockMvc.perform(get("/api/registrations/me").param("eventId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.eventId").exists());

        verifyNoInteractions(service);
    }

    @Test
    void anonymousCallIsAnUnauthenticatedProblem() throws Exception {
        anonymous.perform(get("/api/registrations/{id}", registration.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    @Test
    void registerReturnsCreatedWithLocation() throws Exception {
        when(service.register(eventId, userId)).thenReturn(registration);

        register(eventId)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/registrations/" + registration.getId())))
                .andExpect(jsonPath("$.id").value(registration.getId().toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.cancelledAt").value(nullValue()));
    }

    @Test
    void registerRejectsMissingIds() throws Exception {
        mockMvc.perform(post("/api/registrations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.eventId").exists());
    }

    @Test
    void fullEventIsAConflict() throws Exception {
        when(service.register(eventId, userId)).thenThrow(new EventFullException(eventId));

        register(eventId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_FULL"));
    }

    @Test
    void unavailableDependencyIsA503WithoutInternalDetails() throws Exception {
        when(service.register(eventId, userId))
                .thenThrow(new DownstreamUnavailableException("event-service", "read timed out"));

        register(eventId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail", not(containsString("event-service"))));
    }

    @Test
    void getReturnsRegistration() throws Exception {
        when(service.get(registration.getId(), userId)).thenReturn(registration);

        mockMvc.perform(get("/api/registrations/{id}", registration.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
    }

    @Test
    void unknownRegistrationIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(service.get(unknown, userId)).thenThrow(new RegistrationNotFoundException(unknown));

        mockMvc.perform(get("/api/registrations/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_FOUND"));
    }

    @Test
    void malformedIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/registrations/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelReturnsCancelledRegistration() throws Exception {
        Registration cancelled = Registration.confirm(UUID.randomUUID(), eventId, userId, NOW);
        cancelled.cancel(NOW.plusSeconds(60));
        when(service.cancel(cancelled.getId(), userId)).thenReturn(cancelled);

        mockMvc.perform(post("/api/registrations/{id}/cancel", cancelled.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-10-27T10:01:00Z"));
    }

    @Test
    void cancellingTwiceIsAConflict() throws Exception {
        when(service.cancel(registration.getId(), userId))
                .thenThrow(new RegistrationAlreadyCancelledException(registration.getId()));

        mockMvc.perform(post("/api/registrations/{id}/cancel", registration.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_ALREADY_CANCELLED"));
    }

    @Test
    void concurrentCancellationIsAConflict() throws Exception {
        when(service.cancel(registration.getId(), userId))
                .thenThrow(new ObjectOptimisticLockingFailureException(Registration.class, registration.getId()));

        mockMvc.perform(post("/api/registrations/{id}/cancel", registration.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void listByEventIsPagedNewestFirstByDefaultWithTheAttendeeNames() throws Exception {
        when(service.findByEvent(eq(eventId), isNull(), eq(userId), argThat(newestFirst())))
                .thenReturn(new PageImpl<>(List.of(new EventRegistration(registration, "Ada Lovelace")),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/registrations/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(registration.getId().toString()))
                .andExpect(jsonPath("$.content[0].attendeeName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void listByEventFiltersByStatus() throws Exception {
        when(service.findByEvent(eq(eventId), eq(RegistrationStatus.CONFIRMED), eq(userId), argThat(newestFirst())))
                .thenReturn(new PageImpl<>(List.of(new EventRegistration(registration, null)),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/registrations/events/{eventId}", eventId).param("status", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attendeeName").value(nullValue()));
    }

    @Test
    void listByEventRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/registrations/events/{eventId}", eventId).param("status", "PENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.status").exists());

        verifyNoInteractions(service);
    }

    @Test
    void mineRejectsUnsupportedSort() throws Exception {
        mockMvc.perform(get("/api/registrations/me").param("sort", "status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"));
    }

    private ResultActions register(UUID event) throws Exception {
        return mockMvc.perform(post("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"" + event + "\"}"));
    }

    private static ArgumentMatcher<Pageable> newestFirst() {
        return pageable -> {
            Sort.Order order = pageable.getSort().getOrderFor("createdAt");
            return order != null && order.isDescending();
        };
    }
}
