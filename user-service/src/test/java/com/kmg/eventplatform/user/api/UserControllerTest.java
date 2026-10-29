package com.kmg.eventplatform.user.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kmg.eventplatform.user.application.EmailAlreadyUsedException;
import com.kmg.eventplatform.user.application.SavedProfile;
import com.kmg.eventplatform.user.application.UserNotFoundException;
import com.kmg.eventplatform.user.application.UserService;
import com.kmg.eventplatform.user.domain.User;
import com.kmg.eventplatform.user.infrastructure.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID ADA_ID = UUID.randomUUID();
    private static final String PROFILE_JSON = """
            {"fullName": "Ada Lovelace"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService service;

    /** Tokens are built by jwt() below: no decoding, no Keycloak. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static User ada() {
        return User.register(ADA_ID, "Ada Lovelace", "ada@example.com", NOW);
    }

    /** Ada, an attendee, with the e-mail claim of a Keycloak token. */
    private static JwtRequestPostProcessor asAda() {
        return jwt().jwt(token -> token.subject(ADA_ID.toString()).claim("email", "ada@example.com"))
                .authorities(new SimpleGrantedAuthority("ROLE_ATTENDEE"));
    }

    private static JwtRequestPostProcessor asAdmin() {
        return jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void firstSaveOfTheProfileReturns201WithLocation() throws Exception {
        when(service.saveProfile(ADA_ID, "Ada Lovelace", "ada@example.com")).thenReturn(new SavedProfile(ada(), true));

        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/users/me")))
                .andExpect(jsonPath("$.id").value(ADA_ID.toString()))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void laterSaveOfTheProfileReturns200() throws Exception {
        when(service.saveProfile(ADA_ID, "Ada Lovelace", "ada@example.com")).thenReturn(new SavedProfile(ada(), false));

        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void emailInTheBodyIsIgnoredInFavourOfTheToken() throws Exception {
        when(service.saveProfile(ADA_ID, "Ada Lovelace", "ada@example.com")).thenReturn(new SavedProfile(ada(), true));

        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Ada Lovelace", "email": "grace@example.com"}
                                """))
                .andExpect(status().isCreated());

        verify(service).saveProfile(ADA_ID, "Ada Lovelace", "ada@example.com");
    }

    @Test
    void tokenWithoutEmailCannotSaveAProfile() throws Exception {
        mockMvc.perform(put("/api/users/me")
                        .with(jwt().jwt(token -> token.subject(ADA_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CLAIM_MISSING"));

        verifyNoInteractions(service);
    }

    @Test
    void tokenWithABlankEmailCannotSaveAProfile() throws Exception {
        mockMvc.perform(put("/api/users/me")
                        .with(jwt().jwt(token -> token.subject(ADA_ID.toString()).claim("email", " ")))
                        .contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CLAIM_MISSING"));

        verifyNoInteractions(service);
    }

    @Test
    void saveRejectsInvalidPayloadWithFieldErrors() throws Exception {
        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": " "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.fullName").exists());

        verify(service, never()).saveProfile(any(), anyString(), anyString());
    }

    @Test
    void saveRejectsMalformedJson() throws Exception {
        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void saveReturns409WhenTheEmailBelongsToAnotherProfile() throws Exception {
        when(service.saveProfile(any(), anyString(), anyString()))
                .thenThrow(new EmailAlreadyUsedException("ada@example.com"));

        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"));
    }

    @Test
    void concurrentSaveReturns409() throws Exception {
        when(service.saveProfile(any(), anyString(), anyString()))
                .thenThrow(new OptimisticLockingFailureException("stale"));

        mockMvc.perform(put("/api/users/me").with(asAda()).contentType(MediaType.APPLICATION_JSON).content(PROFILE_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void meReturnsTheCallersProfile() throws Exception {
        when(service.getById(ADA_ID)).thenReturn(ada());

        mockMvc.perform(get("/api/users/me").with(asAda()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"));
    }

    @Test
    void meReturns404UntilTheProfileIsSaved() throws Exception {
        when(service.getById(ADA_ID)).thenThrow(new UserNotFoundException(ADA_ID));

        mockMvc.perform(get("/api/users/me").with(asAda()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.userId").value(ADA_ID.toString()));
    }

    @Test
    void deleteMeErasesTheCallersProfile() throws Exception {
        mockMvc.perform(delete("/api/users/me").with(asAda()))
                .andExpect(status().isNoContent());

        verify(service).delete(ADA_ID);
    }

    @Test
    void anonymousCallerHasNoProfile() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(service);
    }

    @Test
    void nonAdministratorsCannotReadOtherProfiles() throws Exception {
        mockMvc.perform(get("/api/users").with(asAda()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/users/{id}", UUID.randomUUID()).with(asAda()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users/{id}", UUID.randomUUID()).with(asAda()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void adminGetsAUser() throws Exception {
        when(service.getById(ADA_ID)).thenReturn(ada());

        mockMvc.perform(get("/api/users/{id}", ADA_ID).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }

    @Test
    void adminGetsA404ForAnUnknownUser() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenThrow(new UserNotFoundException(id));

        mockMvc.perform(get("/api/users/{id}", id).with(asAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void malformedIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/users/{id}", "not-a-uuid").with(asAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listUsesDefaultPaginationAndStablePageContract() throws Exception {
        when(service.list(any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(ada()), invocation.getArgument(0), 1));

        mockMvc.perform(get("/api/users").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(service).list(pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void listCapsPageSizeAt100() throws Exception {
        when(service.list(any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.getArgument(0), 0));

        mockMvc.perform(get("/api/users").param("size", "100000").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void listRejectsNonWhitelistedSortWithoutQueryingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/users").param("sort", "version,asc").with(asAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"))
                .andExpect(jsonPath("$.property").value("version"));

        verify(service, never()).list(any());
    }

    @Test
    void listAcceptsWhitelistedSort() throws Exception {
        when(service.list(any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.getArgument(0), 0));

        mockMvc.perform(get("/api/users").param("sort", "fullName,asc").with(asAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void adminDeletesAProfile() throws Exception {
        mockMvc.perform(delete("/api/users/{id}", ADA_ID).with(asAdmin()))
                .andExpect(status().isNoContent());

        verify(service).delete(ADA_ID);
    }

    @Test
    void deleteReturns404WhenTheProfileDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new UserNotFoundException(id)).when(service).delete(id);

        mockMvc.perform(delete("/api/users/{id}", id).with(asAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(eq(id))).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(get("/api/users/{id}", id).with(asAdmin()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("hunter2"))));
    }
}
