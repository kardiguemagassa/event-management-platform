package com.kmg.eventplatform.user.api;

import static com.kmg.eventplatform.user.infrastructure.openapi.OpenApiResponses.BAD_REQUEST;
import static com.kmg.eventplatform.user.infrastructure.openapi.OpenApiResponses.CONFLICT;
import static com.kmg.eventplatform.user.infrastructure.openapi.OpenApiResponses.NOT_FOUND;

import com.kmg.eventplatform.user.application.SavedProfile;
import com.kmg.eventplatform.user.application.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * {@code /me}: the caller's own profile, identified by the access token. {@code /names}: display names for
 * other services. The other routes are for administrators only (personal data of every user).
 */
@Tag(name = "Users", description = "Application profiles of the users (identity is managed by Keycloak)")
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** Only these properties may be used for sorting (whitelist: predictable, index-backed queries). */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "fullName", "email");

    /** Batch size of the names lookup (one page of registrations) and of the ids filter of the list. */
    private static final int MAX_IDS = 100;

    private static final int MAX_TEXT_LENGTH = 100;

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @Operation(summary = "Get the caller's profile", description = "404 until the profile is saved once (PUT).")
    @ApiResponse(responseCode = "200", description = "Profile found")
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @GetMapping("/me")
    public UserResponse me(@Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        return UserResponse.from(service.getById(userId(caller)));
    }

    @Operation(summary = "Create or update the caller's profile",
            description = "Idempotent. The e-mail is copied from the access token on every save.")
    @ApiResponse(responseCode = "201", description = "Profile created")
    @ApiResponse(responseCode = "200", description = "Profile updated")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @PutMapping("/me")
    public ResponseEntity<UserResponse> saveMe(@Valid @RequestBody ProfileRequest request,
                                               @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        SavedProfile saved = service.saveProfile(userId(caller), request.fullName(), email(caller));
        UserResponse body = UserResponse.from(saved.user());
        return saved.created()
                ? ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest().build().toUri()).body(body)
                : ResponseEntity.ok(body);
    }

    @Operation(summary = "Delete the caller's profile",
            description = "Right to erasure: the application profile only, the Keycloak account stays.")
    @ApiResponse(responseCode = "204", description = "Profile deleted")
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        service.delete(userId(caller));
    }

    @Operation(summary = "Display names of users",
            description = "Internal: requires the PROFILE_READER role (service account of registration-service), "
                    + "closed at the gateway. Users without a profile are left out; no e-mail is returned.")
    @ApiResponse(responseCode = "200", description = "Names of the users found, in no particular order")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @GetMapping("/names")
    public List<UserNameResponse> names(
            @Parameter(description = "User ids, comma-separated (max " + MAX_IDS + ")")
            @RequestParam(required = false) List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> distinct = new HashSet<>(ids);
        if (distinct.size() > MAX_IDS) {
            throw new InvalidQueryParameterException("ids", "At most " + MAX_IDS + " ids");
        }
        return service.findAllById(distinct).stream().map(UserNameResponse::from).toList();
    }

    @Operation(summary = "Get a user by id", description = "Requires the ADMIN role.")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @GetMapping("/{id}")
    public UserResponse get(@Parameter(description = "User id") @PathVariable UUID id) {
        return UserResponse.from(service.getById(id));
    }

    @Operation(summary = "List users", description = "Requires the ADMIN role. Filters are optional and combined "
            + "with AND. Paginated (default size 20, max 100). Sortable by: createdAt (default, desc), fullName, email.")
    @ApiResponse(responseCode = "200", description = "Page of users")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @GetMapping
    public PageResponse<UserResponse> list(
            @Parameter(description = "Name or e-mail containing this text, case-insensitive (max "
                    + MAX_TEXT_LENGTH + " characters)", example = "dupont")
            @RequestParam(required = false) String q,
            @Parameter(description = "Only these users (max " + MAX_IDS + " ids, comma-separated)")
            @RequestParam(required = false) List<UUID> ids,
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable.getSort());
        if (q != null && q.strip().length() > MAX_TEXT_LENGTH) {
            throw new InvalidQueryParameterException("q", "At most " + MAX_TEXT_LENGTH + " characters");
        }
        Set<UUID> distinct = ids == null ? Set.of() : new HashSet<>(ids);
        if (distinct.size() > MAX_IDS) {
            throw new InvalidQueryParameterException("ids", "At most " + MAX_IDS + " ids");
        }
        return PageResponse.from(service.list(q, distinct, pageable).map(UserResponse::from));
    }

    @Operation(summary = "Delete a user's profile", description = "Requires the ADMIN role. Audited. The Keycloak "
            + "account stays; registrations stay, without the name.")
    @ApiResponse(responseCode = "204", description = "User deleted")
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "User id") @PathVariable UUID id,
                       @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        service.deleteAsAdministrator(id, userId(caller));
    }

    /** Keycloak user id of the caller: every route but the internal names lookup requires a user token. */
    private static UUID userId(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }

    private static String email(Jwt caller) {
        String email = caller.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new EmailClaimMissingException();
        }
        return email;
    }

    private static void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new InvalidSortPropertyException(order.getProperty(), SORTABLE_PROPERTIES);
            }
        }
    }
}
