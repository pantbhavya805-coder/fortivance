package com.fortivance.vendor;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Validated criteria for GET /api/vendors/search: name search, filters and paging.
 * Built from raw request parameters so bad values give a clear 400 instead of an empty page.
 */
public record VendorSearch(String q, String tier, String scope, String type, String flag, int page, int size) {

    static final int DEFAULT_SIZE = 25;
    static final int MAX_SIZE = 100;
    static final int MAX_QUERY_LENGTH = 200;
    static final Set<String> TIERS = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    static final Set<String> SCOPES = Set.of("IN_SCOPE", "EXCLUDED", "UNDER_REVIEW");
    static final Set<String> TYPES = Set.of("all", "direct", "subcontractor");
    static final Set<String> FLAGS = Set.of("overdue", "expired");

    public static VendorSearch of(String q, String tier, String scope, String type, String flag,
                                  Integer page, Integer size) {
        String query = blankToNull(q);
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(BAD_REQUEST, "q must be at most " + MAX_QUERY_LENGTH + " characters.");
        }
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) throw new ResponseStatusException(BAD_REQUEST, "page must be 0 or more.");
        if (s < 1 || s > MAX_SIZE) throw new ResponseStatusException(BAD_REQUEST, "size must be between 1 and " + MAX_SIZE + ".");
        String t = choice("type", type, TYPES, false);
        return new VendorSearch(query, choice("tier", tier, TIERS, true), choice("scope", scope, SCOPES, true),
                t == null ? "all" : t, choice("flag", flag, FLAGS, false), p, s);
    }

    /** Sorted by name, then id so pages are stable when names repeat. */
    public Pageable pageable() {
        return PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id").ascending()));
    }

    public Specification<Vendor> specification(LocalDate today) {
        return (root, cq, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (q != null) {
                where.add(cb.like(cb.lower(root.get("name")), "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%", '\\'));
            }
            if (tier != null) where.add(cb.equal(root.get("riskTier"), tier));
            if (scope != null) where.add(cb.equal(root.get("outsourcingScope"), scope));
            if ("direct".equals(type)) where.add(cb.isNull(root.get("parent")));
            if ("subcontractor".equals(type)) where.add(cb.isNotNull(root.get("parent")));
            if ("overdue".equals(flag)) where.add(cb.lessThan(root.get("nextReviewDue"), today));
            if ("expired".equals(flag)) where.add(cb.lessThan(root.get("contractEnd"), today));
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /** % and _ typed by the user are matched literally. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String choice(String name, String value, Set<String> allowed, boolean upper) {
        String v = blankToNull(value);
        if (v == null) return null;
        v = upper ? v.toUpperCase(Locale.ROOT) : v.toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be one of " + allowed.stream().sorted().toList() + ".");
        }
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
