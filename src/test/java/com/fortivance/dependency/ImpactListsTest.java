package com.fortivance.dependency;

import com.fortivance.common.PageResult;
import com.fortivance.dependency.ImpactReport.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpactListsTest {

    static AffectedService svc(String code, String level, String crit, boolean facing, Integer tol) {
        return new AffectedService(code.hashCode(), code, "Service " + code, crit, facing, tol, null, null, level, List.of("APP"));
    }

    static AffectedApplication app(long id, String code, String crit, boolean spof, String via) {
        return new AffectedApplication(id, code, "App " + code, crit, 4, 1, via, "HOSTING", spof, null);
    }

    static ImpactReport report(List<AffectedApplication> apps, List<AffectedService> services) {
        VendorLink v = new VendorLink(1, "V", "HIGH", false, 0, "V");
        return new ImpactReport(v, List.of(v), apps, services,
                new Summary(1, apps.size(), services.size(), 0, 0, 0, null, 0));
    }

    @Test
    void servicesAreOrderedMostUrgentFirst() {
        List<AffectedService> sorted = ImpactLists.servicesByUrgency(List.of(
                svc("D-8", "DEGRADED", "LOW", false, 8),
                svc("X-NULL", "DISRUPTED", "LOW", false, null),
                svc("X-4", "DISRUPTED", "HIGH", false, 4),
                svc("X-1B", "DISRUPTED", "CRITICAL", true, 1),
                svc("X-1A", "DISRUPTED", "CRITICAL", true, 1),
                svc("D-1", "DEGRADED", "CRITICAL", true, 1)));
        assertThat(sorted).extracting(AffectedService::code)
                .containsExactly("X-1A", "X-1B", "X-4", "X-NULL", "D-1", "D-8");
    }

    @Test
    void applicationsReachedTwiceAreListedOnceAndKeepTheSinglePointFlag() {
        List<AffectedApplication> apps = ImpactLists.distinctApplications(List.of(
                app(1, "CBS", "CRITICAL", false, "Vendor"),
                app(2, "CRM", "LOW", false, "Vendor"),
                app(1, "CBS", "CRITICAL", true, "Subcontractor"),
                app(3, "UPI", "HIGH", false, "Vendor"),
                app(4, "OTP", "MEDIUM", true, "Vendor")));
        assertThat(apps).extracting(AffectedApplication::code).containsExactly("CBS", "OTP", "UPI", "CRM");
        AffectedApplication cbs = apps.get(0);
        assertThat(cbs.singlePointOfFailure()).isTrue();
        assertThat(cbs.viaVendorName()).isEqualTo("Vendor");   // the first (closest) route is kept
    }

    @Test
    void servicePagesFilterAndCountExactly() {
        ImpactReport r = report(List.of(), List.of(
                svc("A", "DISRUPTED", "CRITICAL", true, 1),
                svc("B", "DISRUPTED", "HIGH", false, 2),
                svc("C", "DEGRADED", "CRITICAL", true, 3),
                svc("D", "DEGRADED", "LOW", false, 4)));
        assertThat(ImpactLists.servicePage(r, null, null, null).total()).isEqualTo(4);
        assertThat(ImpactLists.servicePage(r, "disrupted", 0, 6).items()).extracting(AffectedService::code).containsExactly("A", "B");
        assertThat(ImpactLists.servicePage(r, "CRITICAL", 0, 6).items()).extracting(AffectedService::code).containsExactly("A", "C");
        assertThat(ImpactLists.servicePage(r, "CUSTOMER_FACING", 0, 6).total()).isEqualTo(2);
        PageResult<AffectedService> second = ImpactLists.servicePage(r, "ALL", 1, 3);
        assertThat(second.items()).extracting(AffectedService::code).containsExactly("D");
        assertThat(second.totalPages()).isEqualTo(2);
    }

    @Test
    void applicationPagesFilter() {
        ImpactReport r = report(List.of(app(1, "CBS", "CRITICAL", false, "V"), app(2, "OTP", "LOW", true, "V")), List.of());
        assertThat(ImpactLists.applicationPage(r, "SINGLE_POINT", 0, 6).items()).extracting(AffectedApplication::code).containsExactly("OTP");
        assertThat(ImpactLists.applicationPage(r, "critical", 0, 6).items()).extracting(AffectedApplication::code).containsExactly("CBS");
    }

    @Test
    void rejectsUnknownFiltersAndBadPaging() {
        ImpactReport r = report(List.of(), List.of());
        assertThatThrownBy(() -> ImpactLists.servicePage(r, "SEVERE", 0, 6))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("filter");
        assertThatThrownBy(() -> ImpactLists.applicationPage(r, "DISRUPTED", 0, 6))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("filter");
        assertThatThrownBy(() -> ImpactLists.servicePage(r, null, -1, 6))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("page");
        assertThatThrownBy(() -> ImpactLists.servicePage(r, null, 0, 101))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("size");
    }
}
