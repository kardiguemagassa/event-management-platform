package com.kmg.eventplatform.user.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kmg.eventplatform.user.application.EmailAlreadyUsedException;
import com.kmg.eventplatform.user.application.RegisterUserCommand;
import com.kmg.eventplatform.user.application.UpdateUserCommand;
import com.kmg.eventplatform.user.application.UserNotFoundException;
import com.kmg.eventplatform.user.application.UserService;
import com.kmg.eventplatform.user.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(UserController.class)
class UserControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService service;

    private static User ada() {
        return User.register("Ada Lovelace", "ada@example.com", NOW);
    }

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        User ada = ada();
        when(service.register(new RegisterUserCommand("Ada Lovelace", "ada@example.com"))).thenReturn(ada);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Ada Lovelace", "email": "ada@example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/users/" + ada.getId())))
                .andExpect(jsonPath("$.id").value(ada.getId().toString()))
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T10:00:00Z"))
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void createRejectsInvalidPayloadWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": " ", "email": "not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.fullName").exists())
                .andExpect(jsonPath("$.errors.email").exists());

        verify(service, never()).register(any());
    }

    @Test
    void createRejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createReturns409WhenEmailAlreadyUsed() throws Exception {
        when(service.register(any())).thenThrow(new EmailAlreadyUsedException("ada@example.com"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Ada Lovelace", "email": "ada@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_USED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void getReturnsUser() throws Exception {
        User ada = ada();
        when(service.getById(ada.getId())).thenReturn(ada);

        mockMvc.perform(get("/api/users/{id}", ada.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"));
    }

    @Test
    void getReturns404WhenUserDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenThrow(new UserNotFoundException(id));

        mockMvc.perform(get("/api/users/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.userId").value(id.toString()));
    }

    @Test
    void getReturns400WhenIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/api/users/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void listUsesDefaultPaginationAndStablePageContract() throws Exception {
        when(service.list(any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(ada()), invocation.getArgument(0), 1));

        mockMvc.perform(get("/api/users"))
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

        mockMvc.perform(get("/api/users").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void listRejectsNonWhitelistedSortWithoutQueryingTheDatabase() throws Exception {
        mockMvc.perform(get("/api/users").param("sort", "version,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_PROPERTY"))
                .andExpect(jsonPath("$.property").value("version"));

        verify(service, never()).list(any());
    }

    @Test
    void listAcceptsWhitelistedSort() throws Exception {
        when(service.list(any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.getArgument(0), 0));

        mockMvc.perform(get("/api/users").param("sort", "fullName,asc"))
                .andExpect(status().isOk());
    }

    @Test
    void updateReturnsUpdatedUser() throws Exception {
        User ada = ada();
        when(service.update(eq(ada.getId()), eq(new UpdateUserCommand("Ada King", "ada.king@example.com"))))
                .thenReturn(ada);

        mockMvc.perform(put("/api/users/{id}", ada.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Ada King", "email": "ada.king@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ada.getId().toString()));
    }

    @Test
    void updateReturns409OnConcurrentModification() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any())).thenThrow(new OptimisticLockingFailureException("stale"));

        mockMvc.perform(put("/api/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Ada King", "email": "ada@example.com"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/users/{id}", id))
                .andExpect(status().isNoContent());

        verify(service).delete(id);
    }

    @Test
    void deleteReturns404WhenUserDoesNotExist() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new UserNotFoundException(id)).when(service).delete(id);

        mockMvc.perform(delete("/api/users/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void unexpectedErrorReturns500WithoutLeakingDetails() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(get("/api/users/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("hunter2"))));
    }
}
