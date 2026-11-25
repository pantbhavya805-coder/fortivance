package com.fortivance.dependency;

import com.fortivance.common.PageResult;
import com.fortivance.dependency.ImpactReport.AffectedApplication;
import com.fortivance.dependency.ImpactReport.AffectedService;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.Predicate;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Ordering, filtering and paging of an impact report's lists, so large registers can be shown a few rows at a time.
 * The report itself is always computed in full; only what is returned is cut down, so totals stay exact.
 */
public final class ImpactLists {

    public static final int DEFAULT_PAGE_SIZE = 6;
    public static final int MAX_PAGE_SIZE = 100;

    private static final List<String> CRITICALITY = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /** Most urgent first: disrupted before degraded, then the shortest impact tolerance, then code. */
    public static final Comparator<AffectedService> SERVICE_URGENCY = Comparator
            .comparing((AffectedService s) -> !"DISRUPTED".equals(s.impactLevel()))
            .thenComparing(AffectedService::impactToleranceHours, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(AffectedService::code, Comparator.nullsLast(Comparator.naturalOrder()));

    /** Single points of failure first, then criticality (CRITICAL to LOW), then code. */
    public static final Comparator<AffectedApplication> APPLICATION_URGENCY = Comparator
            .comparing((AffectedApplication a) -> !a.singlePointOfFailure())
            .thenComparingInt(a -> rank(a.criticality()))
            .thenComparing(AffectedApplication::code, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Map<String, Predicate<AffectedService>> SERVICE_FILTERS = Map.of(
            "ALL", s -> true,
            "DISRUPTED", s -> "DISRUPTED".equals(s.impactLevel()),
            "CRITICAL", s -> "CRITICAL".equals(s.criticality()),
            "CUSTOMER_FACING", AffectedService::customerFacing);

    private static final Map<String, Predicate<AffectedApplication>> APPLICATION_FILTERS = Map.of(
            "ALL", a -> true,
            "CRITICAL", a -> "CRITICAL".equals(a.criticality()),
            "SINGLE_POINT", AffectedApplication::singlePointOfFailure);

    private ImpactLists() { }

    public static List<AffectedService> servicesByUrgency(List<AffectedService> services) {
        return services.stream().sorted(SERVICE_URGENCY).toList();
    }

    /**
     * One entry per application. An application can be reached through more than one vendor in the chain
     * (for example a vendor and its own subcontractor); the entry keeps the first route found (the closest vendor)
     * and counts as a single point of failure if any route is one.
     */
    public static List<AffectedApplication> distinctApplications(List<AffectedApplication> apps) {
        Map<Long, AffectedApplication> byId = new LinkedHashMap<>();
        for (AffectedApplication a : apps) {
            byId.merge(a.id(), a, (first, other) -> other.singlePointOfFailure() && !first.singlePointOfFailure()
                    ? new AffectedApplication(first.id(), first.code(), first.name(), first.criticality(),
                            first.rtoHours(), first.viaVendorId(), first.viaVendorName(), first.dependencyType(),
                            true, first.alternative())
                    : first);
        }
        return byId.values().stream().sorted(APPLICATION_URGENCY).toList();
    }

    public static PageResult<AffectedService> servicePage(ImpactReport r, String filter, Integer page, Integer size) {
        Predicate<AffectedService> f = choose("filter", filter, SERVICE_FILTERS);
        return page(servicesByUrgency(r.services()).stream().filter(f).toList(), page, size);
    }

    public static PageResult<AffectedApplication> applicationPage(ImpactReport r, String filter, Integer page, Integer size) {
        Predicate<AffectedApplication> f = choose("filter", filter, APPLICATION_FILTERS);
        return page(distinctApplications(r.applications()).stream().filter(f).toList(), page, size);
    }

    private static <T> PageResult<T> page(List<T> all, Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_PAGE_SIZE : size;
        if (p < 0) throw new ResponseStatusException(BAD_REQUEST, "page must be 0 or more.");
        if (s < 1 || s > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(BAD_REQUEST, "size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        return PageResult.of(all, p, s);
    }

    private static <T> Predicate<T> choose(String name, String value, Map<String, Predicate<T>> options) {
        String key = value == null || value.isBlank() ? "ALL" : value.trim().toUpperCase(Locale.ROOT);
        Predicate<T> p = options.get(key);
        if (p == null) {
            throw new ResponseStatusException(BAD_REQUEST, name + " must be one of " + new TreeSet<>(options.keySet()) + ".");
        }
        return p;
    }

    private static int rank(String criticality) {
        int i = CRITICALITY.indexOf(criticality);
        return i < 0 ? CRITICALITY.size() : i;
    }
}
