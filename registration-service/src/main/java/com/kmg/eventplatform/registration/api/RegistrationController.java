package com.kmg.eventplatform.registration.api;

import com.kmg.eventplatform.registration.application.RegistrationService;
import com.kmg.eventplatform.registration.domain.Registration;
import com.kmg.eventplatform.registration.infrastructure.openapi.OpenApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/registrations")
@Tag(name = "Registrations", description = "Register users to events")
class RegistrationController {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of("createdAt");

    private final RegistrationService service;

    RegistrationController(RegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register a user to an event")
    @ApiResponse(responseCode = "201", description = "Registration confirmed and seat reserved")
    @ApiResponse(responseCode = "400", ref = OpenApiResponses.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiResponses.NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = OpenApiResponses.CONFLICT)
    @ApiResponse(responseCode = "503", ref = OpenApiResponses.SERVICE_UNAVAILABLE)
    ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegistrationRequest request) {
        Registration registration = service.register(request.eventId(), request.userId());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(registration.getId())
                .toUri();
        return ResponseEntity.created(location).body(RegistrationResponse.from(registration));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a registration")
    @ApiResponse(responseCode = "200", description = "Registration found")
    @ApiResponse(responseCode = "400", ref = OpenApiResponses.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiResponses.NOT_FOUND)
    RegistrationResponse get(@PathVariable UUID id) {
        return RegistrationResponse.from(service.get(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a registration and release its seat")
    @ApiResponse(responseCode = "200", description = "Registration cancelled and seat released")
    @ApiResponse(responseCode = "404", ref = OpenApiResponses.NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = OpenApiResponses.CONFLICT)
    @ApiResponse(responseCode = "503", ref = OpenApiResponses.SERVICE_UNAVAILABLE)
    RegistrationResponse cancel(@PathVariable UUID id) {
        return RegistrationResponse.from(service.cancel(id));
    }

    @GetMapping("/events/{eventId}")
    @Operation(summary = "List the registrations of an event")
    @ApiResponse(responseCode = "200", description = "Page of registrations, newest first by default")
    @ApiResponse(responseCode = "400", ref = OpenApiResponses.BAD_REQUEST)
    PageResponse<RegistrationResponse> findByEvent(
            @PathVariable UUID eventId,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(service.findByEvent(eventId, checkSort(pageable)).map(RegistrationResponse::from));
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "List the registrations of a user")
    @ApiResponse(responseCode = "200", description = "Page of registrations, newest first by default")
    @ApiResponse(responseCode = "400", ref = OpenApiResponses.BAD_REQUEST)
    PageResponse<RegistrationResponse> findByUser(
            @PathVariable UUID userId,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(service.findByUser(userId, checkSort(pageable)).map(RegistrationResponse::from));
    }

    private static Pageable checkSort(Pageable pageable) {
        pageable.getSort().stream()
                .map(Sort.Order::getProperty)
                .filter(property -> !SORTABLE_PROPERTIES.contains(property))
                .findFirst()
                .ifPresent(property -> {
                    throw new InvalidSortPropertyException(property);
                });
        return pageable;
    }
}
