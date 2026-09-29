package com.adonis.dto;

import org.springframework.data.domain.Page;

import java.util.Collections;
import java.util.List;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements, int totalPages) {
        return new PageResponse<>(
                content != null ? content : Collections.emptyList(),
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1
        );
    }

    public static <T> PageResponse<T> fromPage(Page<T> springPage) {
        if (springPage == null) {
            return new PageResponse<>(Collections.emptyList(), 0, 0, 0L, 0, true, true);
        }
        return new PageResponse<>(
                springPage.getContent(),
                springPage.getNumber(),
                springPage.getSize(),
                springPage.getTotalElements(),
                springPage.getTotalPages(),
                springPage.isFirst(),
                springPage.isLast()
        );
    }
}
