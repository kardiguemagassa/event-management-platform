package com.kmg.eventplatform.event.api;

import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.BAD_REQUEST;
import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.CONFLICT;
import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.FORBIDDEN;
import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.NOT_FOUND;

import com.kmg.eventplatform.event.application.EventSearch;
import com.kmg.eventplatform.event.application.EventSearch.Scope;
import com.kmg.eventplatform.event.application.EventService;
import com.kmg.eventplatform.event.domain.Event;
import com.kmg.eventplatform.event.domain.EventStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "Events", description = "Manage events and their lifecycle")
@RestController
@RequestMapping("/api/events")
public class EventController {

    /** Only these properties may be used for sorting (whitelist: predictable, index-backed queries). */
    private static final Set<String> SORTABLE_PROPERTIES = Set.of("startAt", "createdAt", "title");

    /** Only accepted value of the organizer filter; the security configuration requires ORGANIZER for it. */
    static final String ORGANIZER_ME = "me";

    /** Only accepted value of the scope filter; the security configuration requires ADMIN for it. */
    static final String SCOPE_ALL = "all";

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private static final int MAX_TEXT_LENGTH = 100;
    private static final int MAX_IDS = 100;

    private final EventService service;

    public EventController(EventService service) {
        this.service = service;
    }

    @Operation(summary = "Create an event", description = "Requires the ORGANIZER role; the caller becomes its "
            + "organizer. Created as DRAFT, visible to its organizer only until published.")
    @ApiResponse(responseCode = "201", description = "Event created; Location header points to the new resource")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request,
                                                @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        Event event = service.create(request.toDetails(), userId(caller));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(event.getId())
                .toUri();
        return ResponseEntity.created(location).body(EventResponse.from(event));
    }

    @Operation(summary = "Get an event by id",
            description = "Public, except drafts: visible to their organizer (and administrators) only.")
    @ApiResponse(responseCode = "200", description = "Event found")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @GetMapping("/{id}")
    public EventResponse get(@Parameter(description = "Event id") @PathVariable UUID id,
                             @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller,
                             @Parameter(hidden = true) Authentication authentication) {
        Event event = isAdministrator(authentication)
                ? service.getById(id)
                : service.getVisible(id, userIdOrNull(caller));
        return EventResponse.from(event);
    }

    @Operation(summary = "List events",
            description = "Paginated (default size 20, max 100). Public events plus the caller's own drafts; filters "
                    + "are optional and combined with AND. Sortable by: startAt (default, asc), createdAt, title.")
    @ApiResponse(responseCode = "200", description = "Page of events")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "401", description = "organizer=me or scope=all without a token")
    @ApiResponse(responseCode = "403", description = "organizer=me without the ORGANIZER role, or scope=all "
            + "without the ADMIN role")
    @GetMapping
    public PageResponse<EventResponse> list(
            @Parameter(description = "Only events in this status") @RequestParam(required = false) EventStatus status,
            @Parameter(description = "Title or location containing this text, case-insensitive (max "
                    + MAX_TEXT_LENGTH + " characters)", example = "paris")
            @RequestParam(required = false) String q,
            @Parameter(description = "Only events starting at or after this instant (ISO-8601)",
                    example = "2026-12-01T00:00:00Z")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "`me`: every event of the caller, drafts and cancelled ones included "
                    + "(ORGANIZER role)", schema = @Schema(allowableValues = ORGANIZER_ME))
            @RequestParam(required = false) String organizer,
            @Parameter(description = "`all`: every event of every organizer, drafts included (ADMIN role); "
                    + "not combined with organizer", schema = @Schema(allowableValues = SCOPE_ALL))
            @RequestParam(required = false) String scope,
            @Parameter(description = "Only these events (batch read, max " + MAX_IDS + " ids, comma-separated); "
                    + "events the caller cannot see are left out")
            @RequestParam(required = false) List<UUID> ids,
            @ParameterObject
            @PageableDefault(size = 20, sort = "startAt", direction = Sort.Direction.ASC) Pageable pageable,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        validateSort(pageable.getSort());
        validateText(q);
        EventSearch search = new EventSearch(status, q, from, scope(organizer, scope), idSet(ids));
        return PageResponse.from(service.list(search, userIdOrNull(caller), pageable).map(EventResponse::from));
    }

    @Operation(summary = "Replace an event", description = "Full replacement. Not allowed once cancelled; "
            + "capacity cannot go below the reserved seats.")
    @ApiResponse(responseCode = "200", description = "Event updated")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "403", ref = FORBIDDEN)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @PutMapping("/{id}")
    public EventResponse update(@Parameter(description = "Event id") @PathVariable UUID id,
                                @Valid @RequestBody EventRequest request,
                                @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        return EventResponse.from(service.update(id, request.toDetails(), userId(caller)));
    }

    @Operation(summary = "Publish an event", description = "DRAFT -> PUBLISHED. Idempotent.")
    @ApiResponse(responseCode = "200", description = "Event published")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "403", ref = FORBIDDEN)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @PostMapping("/{id}/publish")
    public EventResponse publish(@Parameter(description = "Event id") @PathVariable UUID id,
                                 @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        return EventResponse.from(service.publish(id, userId(caller)));
    }

    @Operation(summary = "Cancel an event", description = "DRAFT or PUBLISHED -> CANCELLED. Idempotent. "
            + "By its organizer, or by an administrator (moderation, audited).")
    @ApiResponse(responseCode = "200", description = "Event cancelled")
    @ApiResponse(responseCode = "403", ref = FORBIDDEN)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @PostMapping("/{id}/cancel")
    public EventResponse cancel(@Parameter(description = "Event id") @PathVariable UUID id,
                                @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller,
                                @Parameter(hidden = true) Authentication authentication) {
        Event event = isAdministrator(authentication)
                ? service.cancelAsAdministrator(id, userId(caller))
                : service.cancel(id, userId(caller));
        return EventResponse.from(event);
    }

    @Operation(summary = "Delete an event", description = "Only events without reservations; cancel it otherwise.")
    @ApiResponse(responseCode = "204", description = "Event deleted")
    @ApiResponse(responseCode = "403", ref = FORBIDDEN)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@Parameter(description = "Event id") @PathVariable UUID id,
                       @Parameter(hidden = true) @AuthenticationPrincipal Jwt caller) {
        service.delete(id, userId(caller));
    }

    /** Keycloak user id of an authenticated caller (routes that require a token). */
    private static UUID userId(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }

    /** Keycloak user id of the caller, {@code null} when anonymous (public routes). */
    private static UUID userIdOrNull(Jwt caller) {
        return caller == null ? null : userId(caller);
    }

    private static boolean isAdministrator(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }

    /** The role each scope needs is checked by the security configuration, from the same parameters. */
    private static Scope scope(String organizer, String scope) {
        if (organizer != null && !ORGANIZER_ME.equals(organizer)) {
            throw new InvalidQueryParameterException("organizer", "Only 'me' is supported");
        }
        if (scope != null && !SCOPE_ALL.equals(scope)) {
            throw new InvalidQueryParameterException("scope", "Only 'all' is supported");
        }
        if (organizer != null && scope != null) {
            throw new InvalidQueryParameterException("scope", "Not combined with organizer");
        }
        if (scope != null) {
            return Scope.ALL;
        }
        return organizer != null ? Scope.MINE : Scope.VISIBLE;
    }

    private static Set<UUID> idSet(List<UUID> ids) {
        if (ids == null) {
            return null;
        }
        Set<UUID> distinct = new HashSet<>(ids);
        if (distinct.size() > MAX_IDS) {
            throw new InvalidQueryParameterException("ids", "At most " + MAX_IDS + " ids");
        }
        return distinct;
    }

    private static void validateText(String q) {
        if (q != null && q.strip().length() > MAX_TEXT_LENGTH) {
            throw new InvalidQueryParameterException("q", "At most " + MAX_TEXT_LENGTH + " characters");
        }
    }

    private static void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new InvalidSortPropertyException(order.getProperty(), SORTABLE_PROPERTIES);
            }
        }
    }
}
