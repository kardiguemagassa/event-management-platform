package com.kmg.eventplatform.event.api;

import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.BAD_REQUEST;
import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.CONFLICT;
import static com.kmg.eventplatform.event.infrastructure.openapi.OpenApiResponses.NOT_FOUND;

import com.kmg.eventplatform.event.application.CapacityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Capacity", description = "Seat reservation (REST alternative to messaging, used by registration-service)")
@RestController
@RequestMapping("/api/events/{id}/capacity")
public class CapacityController {

    private final CapacityService service;

    public CapacityController(CapacityService service) {
        this.service = service;
    }

    @Operation(summary = "Reserve one seat",
            description = "Atomic: never overbooks. Idempotent per reservationId: a replay returns ALREADY_RESERVED.")
    @ApiResponse(responseCode = "200", description = "Seat reserved (or already reserved by this reservationId)")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @ApiResponse(responseCode = "409", ref = CONFLICT)
    @PostMapping("/reserve")
    public CapacityResponse reserve(@Parameter(description = "Event id") @PathVariable UUID id,
                                    @Valid @RequestBody CapacityRequest request) {
        return new CapacityResponse(id, request.reservationId(), service.reserve(id, request.reservationId()));
    }

    @Operation(summary = "Release one seat",
            description = "Compensation for a failed registration. Idempotent: an unknown or already released "
                    + "reservationId returns NOT_RESERVED.")
    @ApiResponse(responseCode = "200", description = "Seat released (or nothing to release)")
    @ApiResponse(responseCode = "400", ref = BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = NOT_FOUND)
    @PostMapping("/release")
    public CapacityResponse release(@Parameter(description = "Event id") @PathVariable UUID id,
                                    @Valid @RequestBody CapacityRequest request) {
        return new CapacityResponse(id, request.reservationId(), service.release(id, request.reservationId()));
    }
}
