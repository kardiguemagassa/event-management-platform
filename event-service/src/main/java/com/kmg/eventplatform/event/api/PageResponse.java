package com.kmg.eventplatform.event.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable pagination contract (serializing Spring's PageImpl directly is discouraged). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
