package com.fortivance.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final JdbcTemplate jdbcTemplate;

    public MetricsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> metrics() {
        Map<String, Object> result = new HashMap<>();

        // 1. evidenceCoveragePct
        try {
            Long metCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM evidence_mappings WHERE status = 'MET' AND review_status IN ('APPROVED', 'EDITED')",
                    Long.class
            );
            Long inDemoReqs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM control_requirements WHERE in_demo = TRUE",
                    Long.class
            );
            Long vendorsWithContract = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT vendor_id) FROM documents WHERE doc_type = 'CONTRACT'",
                    Long.class
            );
            long denom = (inDemoReqs != null ? inDemoReqs : 0) * (vendorsWithContract != null ? vendorsWithContract : 0);
            if (denom > 0 && metCount != null) {
                double pct = Math.round(((double) metCount / denom * 100.0) * 10.0) / 10.0;
                result.put("evidenceCoveragePct", pct);
            } else {
                result.put("evidenceCoveragePct", null);
            }
        } catch (Exception e) {
            result.put("evidenceCoveragePct", null);
        }

        // 2. dependencyVisibilityPct
        try {
            Long visibleCriticalServices = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT bs.id) FROM business_services bs " +
                    "JOIN service_application_dependencies sad ON bs.id = sad.business_service_id " +
                    "JOIN application_vendor_dependencies avd ON sad.application_id = avd.application_id " +
                    "WHERE bs.criticality = 'CRITICAL'",
                    Long.class
            );
            Long totalCriticalServices = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM business_services WHERE criticality = 'CRITICAL'",
                    Long.class
            );
            if (totalCriticalServices != null && totalCriticalServices > 0 && visibleCriticalServices != null) {
                double pct = Math.round(((double) visibleCriticalServices / totalCriticalServices * 100.0) * 10.0) / 10.0;
                result.put("dependencyVisibilityPct", pct);
            } else {
                result.put("dependencyVisibilityPct", null);
            }
        } catch (Exception e) {
            result.put("dependencyVisibilityPct", null);
        }

        // 3. avgImpactAnalysisMinutes
        try {
            Double avgImpact = jdbcTemplate.queryForObject(
                    "SELECT AVG(TIMESTAMPDIFF(SECOND, i.received_at, e.first_impact) / 60.0) " +
                    "FROM incident_notices i " +
                    "JOIN ( " +
                    "    SELECT incident_id, MIN(occurred_at) as first_impact " +
                    "    FROM incident_events " +
                    "    WHERE event_type = 'IMPACT_COMPUTED' " +
                    "    GROUP BY incident_id " +
                    ") e ON i.id = e.incident_id " +
                    "WHERE i.received_at IS NOT NULL AND e.first_impact >= i.received_at",
                    Double.class
            );
            if (avgImpact != null) {
                result.put("avgImpactAnalysisMinutes", Math.round(avgImpact * 100.0) / 100.0);
            } else {
                result.put("avgImpactAnalysisMinutes", null);
            }
        } catch (Exception e) {
            result.put("avgImpactAnalysisMinutes", null);
        }

        // 4. avgTimeToFirstActionMinutes
        try {
            Double avgAction = jdbcTemplate.queryForObject(
                    "SELECT AVG(TIMESTAMPDIFF(SECOND, i.received_at, e.first_action) / 60.0) " +
                    "FROM incident_notices i " +
                    "JOIN ( " +
                    "    SELECT incident_id, MIN(occurred_at) as first_action " +
                    "    FROM incident_events " +
                    "    WHERE event_type = 'ACTIONS_ASSIGNED' " +
                    "    GROUP BY incident_id " +
                    ") e ON i.id = e.incident_id " +
                    "WHERE i.received_at IS NOT NULL AND e.first_action >= i.received_at",
                    Double.class
            );
            if (avgAction != null) {
                result.put("avgTimeToFirstActionMinutes", Math.round(avgAction * 100.0) / 100.0);
            } else {
                result.put("avgTimeToFirstActionMinutes", null);
            }
        } catch (Exception e) {
            result.put("avgTimeToFirstActionMinutes", null);
        }

        // 5. expiredEvidenceCount
        try {
            Long expiredDocs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE valid_until IS NOT NULL AND valid_until < CURRENT_DATE()",
                    Long.class
            );
            Long overdueVendors = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM vendors WHERE next_review_due IS NOT NULL AND next_review_due < CURRENT_DATE()",
                    Long.class
            );
            long totalExpired = (expiredDocs != null ? expiredDocs : 0) + (overdueVendors != null ? overdueVendors : 0);
            result.put("expiredEvidenceCount", totalExpired);
        } catch (Exception e) {
            result.put("expiredEvidenceCount", null);
        }

        // 6. openActions
        try {
            Long openActs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM actions WHERE status IN ('OPEN', 'IN_PROGRESS')",
                    Long.class
            );
            result.put("openActions", openActs != null ? openActs : 0);
        } catch (Exception e) {
            result.put("openActions", null);
        }

        return result;
    }
}
