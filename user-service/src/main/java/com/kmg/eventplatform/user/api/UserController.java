package com.kmg.eventplatform.user.api;

import static com.kmg.eventplatform.user.infrastructure.openapi.OpenApiResponses.BAD_REQUEST;
import static com.kmg.eventplatform.user.infrastructure.openapi.OpenApiResponses.CONFLICT;
import static com.kmg.eventplatform.user.infrastructure.openapi.OpenApiResponses.NOT_FOUND;

import com.kmg.eventplatform.user.application.UserService;
import com.kmg.eventplatform.user.domain.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "Users", description = "Manage platform users")
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** Only these properties may be used for sorting (whitelist: predictable, index-backed queries). */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt", "fullName", "email");

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @Operation(summary = "Create a user", description = "The email is normalized (trimmed, lower-cased) and must be unique.")
    @ApiResponse(responseCode = "201", description = "User created; Location header points to the new resource")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        User user = service.register(request.toCommand());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(user.getId())
                .toUri();
        return ResponseEntity.created(location).body(UserResponse.from(user));
    }

    @Operation(summary = "Get a user by id")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @GetMapping("/{id}")
    public UserResponse get(@Parameter(description = "User id") @PathVariable UUID id) {
        return UserResponse.from(service.getById(id));
    }

    @Operation(summary = "List users",
            description = "Paginated (default size 20, max 100). Sortable by: createdAt (default, desc), fullName, email.")
    @ApiResponse(responseCode = "200", description = "Page of users")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @GetMapping
    public PageResponse<UserResponse> list(
            @ParameterObject
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable.getSort());
        return PageResponse.from(service.list(pageable).map(UserResponse::from));
    }

    @Operation(summary = "Replace a user", description = "Full replacement: every field is required.")
    @ApiResponse(responseCode = "200", description = "User updated")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @PutMapping("/{id}")
    public UserResponse update(@Parameter(description = "User id") @PathVariable UUID id,
                               @Valid @RequestBody UpdateUserRequest request) {
        return UserResponse.from(service.update(id, request.toCommand()));
    }

    @Operation(summary = "Delete a user")
    @ApiResponse(responseCode = "204", description = "User deleted")
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "User id") @PathVariable UUID id) {
        service.delete(id);
    }

    private static void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new InvalidSortPropertyException(order.getProperty(), SORTABLE_PROPERTIES);
            }
        }
    }
}
