package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.application.DownstreamUnavailableException;
import com.kmg.eventplatform.registration.application.EventFullException;
import com.kmg.eventplatform.registration.application.RegistrationNotFoundException;
import com.kmg.eventplatform.registration.application.RegistrationService;
import com.kmg.eventplatform.registration.application.UserNotFoundException;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.domain.RegistrationAlreadyCancelledException;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
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

    /** Every request carries a valid access token: these tests are about the API contract. */
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
                .defaultRequest(get("/").with(jwt()))
                .build();
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

        register(eventId, userId)
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
                .andExpect(jsonPath("$.errors.eventId").exists())
                .andExpect(jsonPath("$.errors.userId").exists());
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        when(service.register(eventId, userId)).thenThrow(new UserNotFoundException(userId));

        register(eventId, userId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void fullEventIsAConflict() throws Exception {
        when(service.register(eventId, userId)).thenThrow(new EventFullException(eventId));

        register(eventId, userId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_FULL"));
    }

    @Test
    void unavailableDependencyIsA503WithoutInternalDetails() throws Exception {
        when(service.register(eventId, userId))
                .thenThrow(new DownstreamUnavailableException("event-service", "read timed out"));

        register(eventId, userId)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOWNSTREAM_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail", not(containsString("event-service"))));
    }

    @Test
    void getReturnsRegistration() throws Exception {
        when(service.get(registration.getId())).thenReturn(registration);

        mockMvc.perform(get("/api/registrations/{id}", registration.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
    }

    @Test
    void unknownRegistrationIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(service.get(unknown)).thenThrow(new RegistrationNotFoundException(unknown));

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
        when(service.cancel(cancelled.getId())).thenReturn(cancelled);

        mockMvc.perform(post("/api/registrations/{id}/cancel", cancelled.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").value("2026-10-27T10:01:00Z"));
    }

    @Test
    void cancellingTwiceIsAConflict() throws Exception {
        when(service.cancel(registration.getId()))
                .thenThrow(new RegistrationAlreadyCancelledException(registration.getId()));

        mockMvc.perform(post("/api/registrations/{id}/cancel", registration.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_ALREADY_CANCELLED"));
    }

    @Test
    void concurrentCancellationIsAConflict() throws Exception {
        when(service.cancel(registration.getId()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Registration.class, registration.getId()));

        mockMvc.perform(post("/api/registrations/{id}/cancel", registration.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void listByEventIsPagedNewestFirstByDefault() throws Exception {
        when(service.findByEvent(eq(eventId), argThat(newestFirst())))
                .thenReturn(new PageImpl<>(List.of(registration), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/registrations/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(registration.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void listByUserRejectsUnsupportedSort() throws Exception {
        mockMvc.perform(get("/api/registrations/users/{userId}", userId).param("sort", "status"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"));
    }

    private ResultActions register(UUID event, UUID user) throws Exception {
        return mockMvc.perform(post("/api/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"" + event + "\",\"userId\":\"" + user + "\"}"));
    }

    private static ArgumentMatcher<Pageable> newestFirst() {
        return pageable -> {
            Sort.Order order = pageable.getSort().getOrderFor("createdAt");
            return order != null && order.isDescending();
        };
    }
}
