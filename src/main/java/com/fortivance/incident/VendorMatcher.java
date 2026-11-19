package com.fortivance.incident;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** Deterministic vendor resolution and sender checks (no tokens spent). */
@Component
public class VendorMatcher {

    public record VendorRow(long id, String name, String emailDomain, Long parentId, String key) { }

    public record Match(VendorRow reporter, VendorRow affected, List<String> flags) { }

    private final JdbcTemplate jdbc;

    public VendorMatcher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<VendorRow> all() {
        return jdbc.query("SELECT id, name, email_domain, parent_vendor_id FROM vendors", (rs, i) -> {
            String name = rs.getString("name");
            return new VendorRow(rs.getLong("id"), name, rs.getString("email_domain"),
                    (Long) rs.getObject("parent_vendor_id"), key(name));
        });
    }

    public Match match(String reportingName, String affectedName, String senderEmail) {
        List<VendorRow> vendors = all();
        List<String> flags = new ArrayList<>();
        String domain = domainOf(senderEmail);

        VendorRow byDomain = domain == null ? null :
                vendors.stream().filter(v -> domain.equalsIgnoreCase(v.emailDomain())).findFirst().orElse(null);
        VendorRow byName = byName(vendors, reportingName);
        VendorRow reporter = byDomain != null ? byDomain : byName;

        if (reporter == null) {
            flags.add("REPORTER_NOT_IN_REGISTRY");
        } else if (domain == null) {
            flags.add("SENDER_UNKNOWN");
        } else if (!domain.equalsIgnoreCase(reporter.emailDomain())) {
            flags.add("SENDER_DOMAIN_MISMATCH: " + domain + " is not the registered domain " + reporter.emailDomain());
        }

        VendorRow affected = byName(vendors, affectedName);
        if (affected == null) affected = reporter;

        if (reporter != null && affected != null && affected.id() != reporter.id()
                && !Objects.equals(affected.parentId(), reporter.id())) {
            flags.add("AFFECTED_VENDOR_NOT_IN_REPORTER_CHAIN");
        }
        return new Match(reporter, affected, flags);
    }

    private static VendorRow byName(List<VendorRow> vendors, String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        return vendors.stream().filter(v -> n.contains(v.key())).findFirst().orElse(null);
    }

    /** "NimbusCloud Infra Pvt Ltd" -> "nimbuscloud" */
    private static String key(String name) {
        return name.toLowerCase().split("\\s+")[0].replaceAll("[^a-z0-9]", "");
    }

    static String domainOf(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at < 0 ? null : email.substring(at + 1).trim().toLowerCase().replaceAll("[>\\s]+$", "");
    }
}
