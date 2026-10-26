package com.kmg.eventplatform.event.api;

import com.kmg.eventplatform.event.domain.EventDetails;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Used for creation and full replacement (PUT): every required field must be present. */
public record EventRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 200) String location,
        @NotNull @Future Instant startAt,
        @NotNull @Min(1) @Max(100_000) Integer capacity) {

    EventDetails toDetails() {
        return new EventDetails(title, description, location, startAt, capacity);
    }
}
