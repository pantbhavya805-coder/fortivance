package com.fortivance.vendor;

import com.fortivance.common.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/vendors")
@Transactional(readOnly = true)
public class VendorController {

    private final VendorRepository vendors;
    private final JdbcTemplate jdbcTemplate;

    public VendorController(VendorRepository vendors, JdbcTemplate jdbcTemplate) {
        this.vendors = vendors;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public List<Vendor> list(@RequestParam(defaultValue = "false") boolean directOnly) {
        return directOnly ? vendors.findByParentIsNull() : vendors.findAll();
    }

    /**
     * One page of vendors, sorted by name, for registers too large to list at once.
     * q matches anywhere in the name; tier, scope, type (all/direct/subcontractor) and flag (overdue/expired) filter.
     */
    @GetMapping("/search")
    public PageResult<Vendor> search(@RequestParam(required = false) String q,
                                     @RequestParam(required = false) String tier,
                                     @RequestParam(required = false) String scope,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String flag,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        VendorSearch s = VendorSearch.of(q, tier, scope, type, flag, page, size);
        return PageResult.of(vendors.findAll(s.specification(LocalDate.now()), s.pageable()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Vendor> get(@PathVariable Long id) {
        return ResponseEntity.of(vendors.findById(id));
    }

    /** Fourth parties: subcontractors sitting behind this vendor. */
    @GetMapping("/{id}/subcontractors")
    public List<Vendor> subcontractors(@PathVariable Long id) {
        return vendors.findByParentId(id);
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<Map<String, Object>> profile(@PathVariable Long id) {
        Optional<Vendor> vendorOpt = vendors.findById(id);
        if (vendorOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Vendor vendor = vendorOpt.get();

        // 1. Documents
        String docSql = "SELECT id, doc_type, title, issued_on, valid_until, processing_status FROM documents WHERE vendor_id = ? ORDER BY id";
        List<Map<String, Object>> documents = jdbcTemplate.query(docSql, (rs, rowNum) -> {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", rs.getLong("id"));
            doc.put("docType", rs.getString("doc_type"));
            doc.put("title", rs.getString("title"));
            doc.put("issuedOn", rs.getDate("issued_on") != null ? rs.getDate("issued_on").toLocalDate() : null);
            doc.put("validUntil", rs.getDate("valid_until") != null ? rs.getDate("valid_until").toLocalDate() : null);
            doc.put("processingStatus", rs.getString("processing_status"));
            return doc;
        }, id);

        // 2. Dependent applications
        String appSql = "SELECT a.id, a.name, a.code, a.criticality, a.owner_team, a.rto_hours, a.rpo_minutes, " +
                "avd.dependency_type, avd.is_single_point, avd.alternative_vendor " +
                "FROM application_vendor_dependencies avd " +
                "JOIN applications a ON avd.application_id = a.id " +
                "WHERE avd.vendor_id = ?";
        List<Map<String, Object>> dependentApps = jdbcTemplate.query(appSql, (rs, rowNum) -> {
            Map<String, Object> app = new LinkedHashMap<>();
            app.put("id", rs.getLong("id"));
            app.put("name", rs.getString("name"));
            app.put("code", rs.getString("code"));
            app.put("criticality", rs.getString("criticality"));
            app.put("ownerTeam", rs.getString("owner_team"));
            app.put("rtoHours", rs.getObject("rto_hours"));
            app.put("rpoMinutes", rs.getObject("rpo_minutes"));
            app.put("dependencyType", rs.getString("dependency_type"));
            app.put("isSinglePoint", rs.getBoolean("is_single_point"));
            app.put("alternativeVendor", rs.getString("alternative_vendor"));
            return app;
        }, id);

        // 3. Open actions count
        Long openActions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM actions WHERE vendor_id = ? AND status IN ('OPEN', 'IN_PROGRESS')",
                Long.class,
                id
        );

        // 4. Flags
        boolean reviewOverdue = vendor.getNextReviewDue() != null && vendor.getNextReviewDue().isBefore(LocalDate.now());
        boolean contractExpired = vendor.getContractEnd() != null && vendor.getContractEnd().isBefore(LocalDate.now());
        boolean hasCriticalDep = dependentApps.stream().anyMatch(a -> "CRITICAL".equalsIgnoreCase(String.valueOf(a.get("criticality"))));
        boolean sensitiveData = "CUSTOMER_PII".equalsIgnoreCase(vendor.getDataAccess()) || "FINANCIAL".equalsIgnoreCase(vendor.getDataAccess());
        boolean possibleMisTier = sensitiveData && !vendor.isMaterial() && hasCriticalDep;

        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("reviewOverdue", reviewOverdue);
        flags.put("contractExpired", contractExpired);
        flags.put("possibleMisTier", possibleMisTier);

        // Subcontractors
        List<Vendor> subcontractors = vendors.findByParentId(id);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", vendor.getId());
        response.put("bankId", vendor.getBankId());
        response.put("name", vendor.getName());
        response.put("serviceCategory", vendor.getServiceCategory());
        response.put("serviceDescription", vendor.getServiceDescription());
        response.put("riskTier", vendor.getRiskTier());
        response.put("isMaterial", vendor.isMaterial());
        response.put("material", vendor.isMaterial());
        response.put("country", vendor.getCountry());
        response.put("dataAccess", vendor.getDataAccess());
        response.put("contractStart", vendor.getContractStart());
        response.put("contractEnd", vendor.getContractEnd());
        response.put("lastAssessedOn", vendor.getLastAssessedOn());
        response.put("nextReviewDue", vendor.getNextReviewDue());
        response.put("status", vendor.getStatus());
        response.put("emailDomain", vendor.getEmailDomain());
        response.put("outsourcingScope", vendor.getOutsourcingScope());
        response.put("scopeNote", vendor.getScopeNote());
        response.put("createdAt", vendor.getCreatedAt());
        response.put("isFourthParty", vendor.isFourthParty());

        if (vendor.getParent() != null) {
            Vendor p = vendor.getParent();
            response.put("parentVendor", Map.of("id", p.getId(), "name", p.getName(), "riskTier", p.getRiskTier()));
        } else {
            response.put("parentVendor", null);
        }

        response.put("subcontractors", subcontractors);
        response.put("documents", documents);
        response.put("applications", dependentApps);
        response.put("dependentApplications", dependentApps);
        response.put("openActionsCount", openActions != null ? openActions : 0);
        response.put("flags", flags);
        response.put("reviewOverdue", reviewOverdue);
        response.put("contractExpired", contractExpired);
        response.put("possibleMisTier", possibleMisTier);

        return ResponseEntity.ok(response);
    }
}
