package com.fortivance.common;

import org.springframework.data.domain.Page;

import java.util.List;

/** One page of a list endpoint: the items plus what the UI needs for "1-25 of 5,006" and Previous / Next. */
public record PageResult<T>(List<T> items, long total, int page, int size, int totalPages) {

    public static <T> PageResult<T> of(Page<T> p) {
        return new PageResult<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize(), p.getTotalPages());
    }

    /** Pages an in-memory list, for results that are computed rather than read with LIMIT/OFFSET. */
    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        int totalPages = (all.size() + size - 1) / size;
        return new PageResult<>(List.copyOf(all.subList(from, to)), all.size(), page, size, totalPages);
    }
}
