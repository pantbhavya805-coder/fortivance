package com.fortivance.dependency;

import com.fortivance.dependency.ImpactReport.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Deterministic impact analysis: no AI involved.
 * An incident at vendor X affects X and every vendor above it in the subcontracting chain
 * (X's parent, the parent's parent, ...). From those vendors we follow
 * application_vendor_dependencies and service_application_dependencies to business services.
 */
@Service
public class ImpactService {

    private static final String VENDOR_CHAIN_SQL = """
            WITH RECURSIVE chain (vendor_id, depth, path) AS (
                SELECT v.id, 0, CAST(v.name AS CHAR(1000))
                FROM vendors v
                WHERE v.id = :vendorId
                UNION ALL
                SELECT p.id, c.depth + 1, CONCAT(c.path, ' -> ', p.name)
                FROM chain c
                JOIN vendors v ON v.id = c.vendor_id
                JOIN vendors p ON p.id = v.parent_vendor_id
                WHERE c.depth < 10
            )
            SELECT c.vendor_id, c.depth, c.path, v.name, v.risk_tier,
                   (v.parent_vendor_id IS NOT NULL) AS fourth_party
            FROM chain c
            JOIN vendors v ON v.id = c.vendor_id
            ORDER BY c.depth
            """;

    private static final String APPLICATIONS_SQL = """
            SELECT a.id, a.code, a.name, a.criticality, a.rto_hours,
                   v.id AS vendor_id, v.name AS vendor_name,
                   avd.dependency_type, avd.is_single_point, avd.alternative_vendor
            FROM application_vendor_dependencies avd
            JOIN applications a ON a.id = avd.application_id
            JOIN vendors v      ON v.id = avd.vendor_id
            WHERE avd.vendor_id IN (:vendorIds)
            ORDER BY FIELD(a.criticality, 'CRITICAL','HIGH','MEDIUM','LOW'), a.code
            """;

    private static final String SERVICES_SQL = """
            SELECT bs.id, bs.code, bs.name, bs.criticality, bs.is_customer_facing,
                   bs.impact_tolerance_hours, bs.bcp_plan_ref, bs.business_owner,
                   a.code AS app_code, sad.is_essential
            FROM service_application_dependencies sad
            JOIN business_services bs ON bs.id = sad.business_service_id
            JOIN applications a       ON a.id  = sad.application_id
            WHERE sad.application_id IN (:appIds)
            ORDER BY bs.impact_tolerance_hours, bs.code, a.code
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ImpactService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ImpactReport analyse(long vendorId) {
        List<VendorLink> chain = jdbc.query(VENDOR_CHAIN_SQL, Map.of("vendorId", vendorId),
                (rs, i) -> new VendorLink(
                        rs.getLong("vendor_id"), rs.getString("name"), rs.getString("risk_tier"),
                        rs.getBoolean("fourth_party"), rs.getInt("depth"), rs.getString("path")));

        if (chain.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Vendor " + vendorId + " not found");
        }

        List<Long> vendorIds = chain.stream().map(VendorLink::id).toList();
        List<AffectedApplication> apps = jdbc.query(APPLICATIONS_SQL,
                new MapSqlParameterSource("vendorIds", vendorIds),
                (rs, i) -> new AffectedApplication(
                        rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("criticality"), (Integer) rs.getObject("rto_hours"),
                        rs.getLong("vendor_id"), rs.getString("vendor_name"),
                        rs.getString("dependency_type"), rs.getBoolean("is_single_point"),
                        rs.getString("alternative_vendor")));

        List<AffectedService> services = apps.isEmpty() ? List.of() : collectServices(apps);

        int disrupted = (int) services.stream().filter(s -> "DISRUPTED".equals(s.impactLevel())).count();
        int critical = (int) services.stream().filter(s -> "CRITICAL".equals(s.criticality())).count();
        int customerFacing = (int) services.stream().filter(AffectedService::customerFacing).count();
        Integer tightest = services.stream().map(AffectedService::impactToleranceHours)
                .filter(Objects::nonNull).min(Integer::compare).orElse(null);
        // Counted per application: one reached through two vendors in the chain is still one single point of failure.
        int spof = (int) ImpactLists.distinctApplications(apps).stream().filter(AffectedApplication::singlePointOfFailure).count();
        int distinctApps = (int) apps.stream().map(AffectedApplication::id).distinct().count();

        return new ImpactReport(chain.get(0), chain, apps, services,
                new Summary(chain.size(), distinctApps, services.size(), disrupted,
                        critical, customerFacing, tightest, spof));
    }

    private List<AffectedService> collectServices(List<AffectedApplication> apps) {
        List<Long> appIds = apps.stream().map(AffectedApplication::id).distinct().toList();

        // One row per (service, app); fold into one entry per service.
        Map<Long, ServiceAccumulator> byService = new LinkedHashMap<>();
        jdbc.query(SERVICES_SQL, new MapSqlParameterSource("appIds", appIds), rs -> {
            long id = rs.getLong("id");
            ServiceAccumulator acc = byService.computeIfAbsent(id, k -> {
                try {
                    return new ServiceAccumulator(id, rs.getString("code"), rs.getString("name"),
                            rs.getString("criticality"), rs.getBoolean("is_customer_facing"),
                            (Integer) rs.getObject("impact_tolerance_hours"),
                            rs.getString("bcp_plan_ref"), rs.getString("business_owner"));
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            acc.viaApps.add(rs.getString("app_code"));
            if (rs.getBoolean("is_essential")) acc.essentialHit = true;
        });

        return byService.values().stream().map(ServiceAccumulator::toRecord).toList();
    }

    private static final class ServiceAccumulator {
        final long id; final String code, name, criticality, bcp, owner;
        final boolean customerFacing; final Integer tolerance;
        final Set<String> viaApps = new LinkedHashSet<>();
        boolean essentialHit;

        ServiceAccumulator(long id, String code, String name, String criticality, boolean customerFacing,
                           Integer tolerance, String bcp, String owner) {
            this.id = id; this.code = code; this.name = name; this.criticality = criticality;
            this.customerFacing = customerFacing; this.tolerance = tolerance; this.bcp = bcp; this.owner = owner;
        }

        AffectedService toRecord() {
            return new AffectedService(id, code, name, criticality, customerFacing, tolerance, bcp, owner,
                    essentialHit ? "DISRUPTED" : "DEGRADED", List.copyOf(viaApps));
        }
    }
}
