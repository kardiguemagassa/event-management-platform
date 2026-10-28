package com.kmg.eventplatform.registration.api;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable pagination contract: Spring Data's Page JSON is an implementation detail.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
